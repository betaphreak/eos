package com.civstudio.server.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.civstudio.server.HostedSession;
import com.civstudio.server.SessionHost;
import com.civstudio.server.SessionSpec;
import com.civstudio.server.command.SetTaxRateCommand;
import com.civstudio.server.registry.SessionRegistry;
import com.civstudio.server.render.SessionSnapshot;
import com.civstudio.settlement.Settlement;

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
 * <li>{@code GET  /api/sessions/{id}/events}  — the retained event tail (a snapshot's log is only
 * the delta, so this is how a late joiner or a reloaded page recovers what it missed).</li>
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

	// ceiling on an /events page, so a caller can't ask for the whole retained tail (4096 lines) at
	// once; 0/absent means the tail's own default (200). The notification board asks for far less —
	// only the last 30 in-game days, which it bounds again client-side.
	private static final int MAX_EVENT_LIMIT = 512;

	private final SessionHost host;
	private final ObjectMapper json;
	private final CurrentUserResolver currentUser;
	private final SessionAuthz authz;

	public SessionController(SessionHost host, ObjectMapper json, CurrentUserResolver currentUser,
			SessionAuthz authz) {
		this.host = host;
		this.json = json;
		this.currentUser = currentUser;
		this.authz = authz;
	}

	@GetMapping
	public List<Map<String, Object>> list() {
		List<Map<String, Object>> out = new ArrayList<>();
		for (HostedSession hs : host.list()) {
			// a mutable map, not Map.of: endReason is null unless the run ended itself, and Map.of
			// rejects null values
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", hs.id());
			row.put("scenario", hs.spec().scenario());
			row.put("seed", hs.spec().seed());
			row.put("state", hs.state().name());
			row.put("tick", hs.tick());
			if (hs.endReason() != null)
				row.put("endReason", hs.endReason());
			out.add(row);
		}
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
		// A Timeline is born EMPTY and opens for joins; starting it here would fire the gun on a
		// world nobody has joined (and launch() rightly refuses an empty run). Its gun is a separate,
		// admin-only act — control {action:"start"}. Every other scenario founds its colony up front
		// and starts immediately, as before.
		if (hs.state() == HostedSession.State.CREATED && !hs.isTimeline())
			hs.start();
		return ResponseEntity.status(201).body(Map.of("id", hs.id(), "state", hs.state().name()));
	}

	@PostMapping("/{id}/control")
	public ResponseEntity<Object> control(@PathVariable String id, @RequestBody ControlRequest req,
			HttpServletRequest http) {
		HostedSession hs = host.getOrRestore(id);
		if (hs == null)
			return notFound(id);
		// play/pause/step/rate/stop change state shared by every spectator, so control requires a
		// signed-in user (any authenticated user for the unowned/public demo; the owner for an owned
		// session). Spectating stays anonymous. See docs/authentication.md.
		ResponseEntity<Object> denied = authz.denyClock(hs, http);
		if (denied != null)
			return denied;
		// a finished run takes no orders: its thread is gone, so pause/resume/step would silently
		// do nothing. Say so rather than returning a cheerful 200 that changed nothing.
		if (hs.state() == HostedSession.State.GAME_OVER)
			return ResponseEntity.status(409).body(Map.of("error", "session " + id + " is over",
					"state", hs.state().name(), "endReason", String.valueOf(hs.endReason())));
		String action = req.action() == null ? "" : req.action();
		switch (action) {
			// the gun: start a Timeline that has been open for joins. Separate from resume (which only
			// un-pauses an already-running session) because this is the moment the roster closes and
			// everyone's colony starts on the same day.
			case "start" -> {
				if (hs.state() != HostedSession.State.CREATED)
					return ResponseEntity.status(409).body(Map.of("error",
							"session " + id + " has already started", "state", hs.state().name()));
				try {
					hs.start();
				} catch (IllegalStateException empty) {
					return ResponseEntity.status(409).body(Map.of("error", empty.getMessage()));
				}
			}
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
		HostedSession hs = host.getOrRestore(id);
		if (hs == null)
			return notFound(id);
		// a command acts on a COLONY, so it is that colony's owner who may submit it — not the run's
		// (docs/spectator-lobby.md Phase 2). They coincide for a single-player run and the demo.
		String colony = req.colony() == null || req.colony().isBlank() ? null : req.colony();
		ResponseEntity<Object> denied = authz.denyColonyCommand(hs, colony, http);
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
				hs.submit(new SetTaxRateCommand(tick, colony, lever, rate));
				Map<String, Object> ok = new LinkedHashMap<>();
				ok.put("accepted", true);
				ok.put("type", type);
				if (colony != null)
					ok.put("colony", colony);
				ok.put("lever", lever.name());
				ok.put("rate", rate);
				ok.put("tick", tick);
				return ResponseEntity.status(202).body(ok);
			}
			default -> {
				return ResponseEntity.badRequest().body(Map.of("error", "unknown command type: " + type));
			}
		}
	}

	// Take your seat in a ranked Timeline: found your colony in the shared world. Requires a
	// signed-in user (the seat is theirs), and only works before the gun — see
	// docs/spectator-lobby.md Phase 3. Idempotent: asking twice returns the seat you already hold.
	@PostMapping("/{id}/join")
	public ResponseEntity<Object> join(@PathVariable String id, HttpServletRequest http) {
		HostedSession hs = host.getOrRestore(id);
		if (hs == null)
			return notFound(id);
		ResponseEntity<Object> denied = authz.denyJoin(http);
		if (denied != null)
			return denied;
		try {
			Settlement colony = host.joinTimeline(id, currentUser.userId(http));
			return ResponseEntity.status(201).body(Map.of("colony", colony.getName(),
					"province", colony.getProvince() == null ? 0 : colony.getProvince().id(),
					"session", id));
		} catch (SessionRegistry.SeatTakenException taken) {
			// the durable record says this player already has a seat — even if this process does not
			// hold their colony. A conflict, and deliberately NOT a silent second seat.
			return ResponseEntity.status(409).body(Map.of("error", taken.getMessage()));
		} catch (IllegalStateException closed) {
			// the roster is closed (the Timeline is running or over) — a real conflict, not a bug
			return ResponseEntity.status(409).body(Map.of("error", closed.getMessage()));
		} catch (IllegalArgumentException bad) {
			return ResponseEntity.badRequest().body(Map.of("error", bad.getMessage()));
		}
	}

	// post a lobby chat message. Unlike control/commands (which change shared state and are
	// owner-gated), chat is open to ANY signed-in user — it's a spectator lobby. The poster's
	// display name is resolved server-side from the principal, so it can't be spoofed; the message
	// is broadcast immediately to every spectator over the SSE `chat` event.
	@PostMapping("/{id}/chat")
	public ResponseEntity<Object> chat(@PathVariable String id, @RequestBody(required = false) ChatRequest req,
			HttpServletRequest http) {
		HostedSession hs = host.getOrRestore(id);
		if (hs == null)
			return notFound(id);
		ResponseEntity<Object> denied = authz.denyChat(http);
		if (denied != null)
			return denied;
		String user = currentUser.displayName(http);
		String text = req == null || req.text() == null ? "" : req.text().strip();
		if (text.isEmpty())
			return ResponseEntity.badRequest().body(Map.of("error", "empty message"));
		if (text.length() > MAX_CHAT_LEN)
			text = text.substring(0, MAX_CHAT_LEN);
		hs.postChat(user, text);
		return ResponseEntity.status(202).body(Map.of("accepted", true, "user", user));
	}

	// the session's latest render snapshot, once. /stream is the right way to WATCH a session, but a
	// caller that wants a single reading — the admin console prefilling the tax levers, a probe, a
	// script — should not have to open an SSE connection and hang up after one frame. Read-only, so
	// ungated like the other spectate endpoints. 204 while the session has not yet emitted a frame.
	@GetMapping("/{id}/snapshot")
	public ResponseEntity<Object> snapshot(@PathVariable String id) {
		HostedSession hs = host.getOrRestore(id);
		if (hs == null)
			return notFound(id);
		SessionSnapshot snap = hs.currentSnapshot();
		return snap == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(snap);
	}

	// The session's retained event tail. A snapshot's `log` is a DELTA — drained once into the frame
	// it rides on — so a spectator only ever sees what the sim logged while it happened to be
	// connected. Join a minute late, or reload the page, and those lines are gone for good: the demo
	// logs its founding, its first digest and two starvation demotions inside its first 62 ticks, and
	// a viewer arriving after that would watch an empty notification board forever.
	//
	// So the board rehydrates from here on connect (web/js/notify.mjs), asking for the window it can
	// actually show — the last 30 in-game days. Read-only, and ungated like the other spectate
	// endpoints. Same tail the get_events MCP tool reads; the lines are byte-identical to the ones the
	// stream delivers (LogLine.of derives curated/sev for both).
	@GetMapping("/{id}/events")
	public ResponseEntity<Object> events(@PathVariable String id,
			@RequestParam(required = false) String level,
			@RequestParam(required = false) String from,
			@RequestParam(required = false) String to,
			@RequestParam(required = false) String grep,
			@RequestParam(required = false, defaultValue = "0") int limit) {
		HostedSession hs = host.getOrRestore(id);
		if (hs == null)
			return notFound(id);
		return ResponseEntity.ok(hs.eventTail(level, from, to, grep, Math.min(limit, MAX_EVENT_LIMIT)));
	}

	// open a Server-Sent-Events stream of the session's snapshots. The session thread serializes +
	// offers frames into this connection's bounded queue (dropping the oldest if the client lags);
	// this connection's own virtual thread drains it to the socket until the client disconnects.
	@GetMapping("/{id}/stream")
	public SseEmitter stream(@PathVariable String id) {
		HostedSession hs = host.getOrRestore(id);
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
					// once the run is over (stopped OR game over) and its final frame is flushed, end
					// the stream (rather than leaving an open async request pinning the server on
					// shutdown). Terminal-state check, not a STOPPED comparison: a GAME_OVER session
					// is just as finished, and would otherwise hold this emitter open forever.
					if (hs.isTerminal() && frames.isEmpty())
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
	private static ResponseEntity<Object> notFound(String id) {
		return ResponseEntity.status(404).body(Map.of("error", "no session " + id));
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

	/**
	 * Body of {@code POST /api/sessions/{id}/commands}. {@code colony} names the colony the command
	 * acts on — required to act in a session holding more than one, since a lever must move the
	 * caller's own colony and no one else's; optional (and meaning "the one colony") in a
	 * single-colony run. See {@code docs/spectator-lobby.md} Phase 2.
	 */
	public record CommandRequest(String type, String colony, String lever, Double rate, Long tick) {
	}

	/** Body of {@code POST /api/sessions/{id}/chat}. */
	public record ChatRequest(String text) {
	}
}
