package com.civstudio.server.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.civstudio.server.HostedSession;
import com.civstudio.server.SessionHost;
import com.civstudio.server.SessionSpec;
import com.civstudio.server.command.SetTaxRateCommand;
import com.civstudio.server.render.SessionSnapshot;

import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.ObjectMapper;

/**
 * The REST + SSE surface of the spectator/interactive server (see {@code docs/client-server.md}),
 * ported from the old JDK-{@code HttpServer} {@code FeedServer} to Spring MVC (running on virtual
 * threads). Endpoints:
 * <ul>
 * <li>{@code GET  /api/sessions}              — list the hosted sessions.</li>
 * <li>{@code POST /api/sessions}              — found + start a session from a spec.</li>
 * <li>{@code GET  /api/sessions/{id}/stream}  — subscribe to the snapshot feed (SSE).</li>
 * <li>{@code POST /api/sessions/{id}/control} — {@code {action:pause|resume|step|rate|stop}}.</li>
 * <li>{@code POST /api/sessions/{id}/commands}— submit a player command, e.g.
 * {@code {type:setTaxRate, lever:bankProfit|nobleIncome, rate:0..1}}.</li>
 * </ul>
 * A slow spectator never stalls the sim: each SSE connection has its own bounded queue that the
 * session thread offers into (dropping the oldest frame if the client falls behind), while the
 * connection's own virtual thread drains it to the socket.
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

	// per-connection SSE buffer depth; if a client falls this far behind, the oldest frames are
	// dropped rather than blocking the session thread
	private static final int SSE_QUEUE_CAPACITY = 64;

	// max lobby chat message length (longer is truncated)
	private static final int MAX_CHAT_LEN = 280;

	private final SessionHost host;
	private final ObjectMapper json;
	private final CurrentUserResolver currentUser;

	public SessionController(SessionHost host, ObjectMapper json, CurrentUserResolver currentUser) {
		this.host = host;
		this.json = json;
		this.currentUser = currentUser;
	}

	@GetMapping
	public List<Map<String, Object>> list() {
		List<Map<String, Object>> out = new ArrayList<>();
		for (HostedSession hs : host.list())
			out.add(Map.of("id", hs.id(), "scenario", hs.spec().scenario(), "seed",
					hs.spec().seed(), "state", hs.state().name(), "tick", hs.tick()));
		return out;
	}

	@PostMapping
	public ResponseEntity<Object> create(@RequestBody(required = false) CreateRequest req,
			HttpServletRequest http) {
		CreateRequest r = req == null ? new CreateRequest(null, null, null) : req;
		long seed = r.seed() != null ? r.seed() : 7654321L;
		String scenario = r.scenario() != null ? r.scenario() : SessionSpec.CARAVAN_DEMO;
		int provinceId = r.provinceId() != null ? r.provinceId() : 4411;
		// the founder owns the session; an anonymous founder creates an unowned/public session
		// (open to all — the demo). Phase 2 (docs/authentication.md) will require login to found.
		String owner = currentUser.userId(http);
		HostedSession hs = host.create(new SessionSpec(seed, scenario, provinceId), owner);
		if (hs.state() == HostedSession.State.CREATED)
			hs.start();
		return ResponseEntity.status(201).body(Map.of("id", hs.id(), "state", hs.state().name()));
	}

	@PostMapping("/{id}/control")
	public ResponseEntity<Object> control(@PathVariable String id, @RequestBody ControlRequest req,
			HttpServletRequest http) {
		HostedSession hs = host.get(id);
		if (hs == null)
			return notFound(id);
		// play/pause/step/rate/stop change state shared by every spectator, so control requires a
		// signed-in user (any authenticated user for the unowned/public demo; the owner for an owned
		// session). Spectating stays anonymous. See docs/authentication.md.
		ResponseEntity<Object> denied = denyWrite(hs, http);
		if (denied != null)
			return denied;
		String action = req.action() == null ? "" : req.action();
		switch (action) {
			case "pause" -> hs.pause();
			case "resume" -> hs.resume();
			case "step" -> hs.step(req.value() != null ? req.value().intValue() : 1);
			case "rate" -> hs.setTickRateMillis(req.value() != null ? req.value() : 1000);
			case "stop" -> hs.stop();
			default -> {
				return ResponseEntity.badRequest().body(Map.of("error", "unknown action: " + action));
			}
		}
		return ResponseEntity.ok(Map.of("id", id, "state", hs.state().name(), "tick", hs.tick()));
	}

	// submit a player command (the interactive seam). A command is tick-stamped and applied at the
	// deterministic top of its tick, so it defaults to the NEXT tick (never retro-mutating the
	// in-flight day) unless a later tick is given.
	@PostMapping("/{id}/commands")
	public ResponseEntity<Object> command(@PathVariable String id, @RequestBody CommandRequest req,
			HttpServletRequest http) {
		HostedSession hs = host.get(id);
		if (hs == null)
			return notFound(id);
		// like control, submitting a command requires a signed-in user (owner for an owned session)
		ResponseEntity<Object> denied = denyWrite(hs, http);
		if (denied != null)
			return denied;
		String type = req.type() == null ? "" : req.type();
		long next = hs.tick() + 1;
		long tick = Math.max(next, req.tick() != null ? req.tick() : next);
		switch (type) {
			case "setTaxRate" -> {
				SetTaxRateCommand.Lever lever = parseLever(req.lever());
				if (lever == null)
					return ResponseEntity.badRequest().body(Map.of("error", "unknown lever: " + req.lever()));
				double rate = req.rate() != null ? req.rate() : 0;
				if (!(rate >= 0 && rate <= 1))
					return ResponseEntity.badRequest().body(Map.of("error", "rate must be in [0,1], got " + rate));
				hs.submit(new SetTaxRateCommand(tick, lever, rate));
				return ResponseEntity.status(202).body(Map.of("accepted", true, "type", type,
						"lever", lever.name(), "rate", rate, "tick", tick));
			}
			default -> {
				return ResponseEntity.badRequest().body(Map.of("error", "unknown command type: " + type));
			}
		}
	}

	// post a lobby chat message. Unlike control/commands (which change shared state and are
	// owner-gated), chat is open to ANY signed-in user — it's a spectator lobby. The poster's
	// display name is resolved server-side from the principal, so it can't be spoofed; the message
	// is broadcast immediately to every spectator over the SSE `chat` event.
	@PostMapping("/{id}/chat")
	public ResponseEntity<Object> chat(@PathVariable String id, @RequestBody(required = false) ChatRequest req,
			HttpServletRequest http) {
		HostedSession hs = host.get(id);
		if (hs == null)
			return notFound(id);
		String user = currentUser.displayName(http);
		if (user == null)
			return ResponseEntity.status(401).body(Map.of("error", "sign in to chat"));
		String text = req == null || req.text() == null ? "" : req.text().strip();
		if (text.isEmpty())
			return ResponseEntity.badRequest().body(Map.of("error", "empty message"));
		if (text.length() > MAX_CHAT_LEN)
			text = text.substring(0, MAX_CHAT_LEN);
		hs.postChat(user, text);
		return ResponseEntity.status(202).body(Map.of("accepted", true, "user", user));
	}

	// open a Server-Sent-Events stream of the session's snapshots. The session thread serializes +
	// offers frames into this connection's bounded queue (dropping the oldest if the client lags);
	// this connection's own virtual thread drains it to the socket until the client disconnects.
	@GetMapping("/{id}/stream")
	public SseEmitter stream(@PathVariable String id) {
		HostedSession hs = host.get(id);
		if (hs == null)
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no session " + id);

		SseEmitter emitter = new SseEmitter(Long.MAX_VALUE); // no timeout — an open-ended feed
		BlockingQueue<Frame> frames = new ArrayBlockingQueue<>(SSE_QUEUE_CAPACITY);
		// snapshots (session thread) and chat (an HTTP poster thread) both enqueue here, dropping the
		// oldest frame if the client has fallen behind — so neither the sim nor a chatter ever blocks
		// on a slow spectator. The connection's own virtual thread drains to the socket.
		AutoCloseable sub = hs.subscribe(snap -> offerFrame(frames, new Frame(null, toJson(snap))));
		AutoCloseable chatSub = hs.subscribeChat(msg -> offerFrame(frames, new Frame("chat", toJson(msg))));
		Thread drainer = Thread.ofVirtual().name("sse-" + id).start(() -> {
			try {
				while (true) {
					Frame f = frames.take();
					SseEmitter.SseEventBuilder ev = SseEmitter.event().data(f.data(), MediaType.TEXT_PLAIN);
					if (f.event() != null)
						ev.name(f.event()); // "chat" → the client's chat listener; null → default snapshot
					emitter.send(ev);
					// once the session has stopped and its final frame is flushed, end the stream
					// (rather than leaving an open async request pinning the server on shutdown)
					if (hs.state() == HostedSession.State.STOPPED && frames.isEmpty())
						break;
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (Exception disconnected) {
				// client closed the tab / emitter completed — fall through to unsubscribe
			} finally {
				closeQuietly(sub);
				closeQuietly(chatSub);
				try {
					emitter.complete();
				} catch (RuntimeException ignored) {
					// completion is best-effort
				}
			}
		});
		Runnable cleanup = () -> {
			closeQuietly(sub);
			closeQuietly(chatSub);
			drainer.interrupt();
		};
		emitter.onCompletion(cleanup);
		emitter.onTimeout(cleanup);
		emitter.onError(t -> cleanup.run());
		return emitter;
	}

	private String toJson(Object value) {
		try {
			return json.writeValueAsString(value);
		} catch (RuntimeException e) {
			return null;
		}
	}

	// drop-oldest enqueue: never block the producer (session thread / chat poster) on a slow client
	private static void offerFrame(BlockingQueue<Frame> q, Frame f) {
		if (f.data() == null)
			return; // a serialization failure — skip this frame rather than enqueue a null
		if (!q.offer(f)) {
			q.poll();
			q.offer(f);
		}
	}

	// one SSE frame: the payload plus its event name — null is the default ("message") event, a
	// snapshot; "chat" is a lobby message the client's chat listener handles separately
	private record Frame(String event, String data) {
	}

	private static void closeQuietly(AutoCloseable sub) {
		try {
			sub.close();
		} catch (Exception ignored) {
			// unsubscribe is best-effort
		}
	}

	// authorize a write (control or command): the caller must be signed in, and — for an owned
	// session — be the owner or an admin. Returns the deny response (401 unauthenticated / 403
	// not-owner) or null when allowed. Read/spectate endpoints (list, stream) are never gated.
	private ResponseEntity<Object> denyWrite(HostedSession hs, HttpServletRequest http) {
		String user = currentUser.userId(http);
		if (user == null)
			return ResponseEntity.status(401).body(Map.of("error", "sign in to control the session"));
		String owner = hs.owner();
		if (owner != null && !owner.equals(user) && !currentUser.isAdmin(http))
			return forbidden(hs.id());
		return null;
	}

	private static ResponseEntity<Object> notFound(String id) {
		return ResponseEntity.status(404).body(Map.of("error", "no session " + id));
	}

	private static ResponseEntity<Object> forbidden(String id) {
		return ResponseEntity.status(403).body(Map.of("error", "not the owner of session " + id));
	}

	// map the wire lever name (camelCase from the browser, or the enum name) to the enum
	private static SetTaxRateCommand.Lever parseLever(String wire) {
		if (wire == null)
			return null;
		return switch (wire) {
			case "bankProfit", "BANK_PROFIT" -> SetTaxRateCommand.Lever.BANK_PROFIT;
			case "nobleIncome", "NOBLE_INCOME" -> SetTaxRateCommand.Lever.NOBLE_INCOME;
			default -> null;
		};
	}

	/** Body of {@code POST /api/sessions} (all fields optional; defaults to the caravan demo). */
	public record CreateRequest(Long seed, String scenario, Integer provinceId) {
	}

	/** Body of {@code POST /api/sessions/{id}/control}. */
	public record ControlRequest(String action, Long value) {
	}

	/** Body of {@code POST /api/sessions/{id}/commands}. */
	public record CommandRequest(String type, String lever, Double rate, Long tick) {
	}

	/** Body of {@code POST /api/sessions/{id}/chat}. */
	public record ChatRequest(String text) {
	}
}
