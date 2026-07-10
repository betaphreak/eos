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

	private final SessionHost host;
	private final ObjectMapper json;

	public SessionController(SessionHost host, ObjectMapper json) {
		this.host = host;
		this.json = json;
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
	public ResponseEntity<Object> create(@RequestBody(required = false) CreateRequest req) {
		CreateRequest r = req == null ? new CreateRequest(null, null, null) : req;
		long seed = r.seed() != null ? r.seed() : 7654321L;
		String scenario = r.scenario() != null ? r.scenario() : SessionSpec.CARAVAN_DEMO;
		int provinceId = r.provinceId() != null ? r.provinceId() : 4411;
		HostedSession hs = host.create(new SessionSpec(seed, scenario, provinceId));
		if (hs.state() == HostedSession.State.CREATED)
			hs.start();
		return ResponseEntity.status(201).body(Map.of("id", hs.id(), "state", hs.state().name()));
	}

	@PostMapping("/{id}/control")
	public ResponseEntity<Object> control(@PathVariable String id, @RequestBody ControlRequest req) {
		HostedSession hs = host.get(id);
		if (hs == null)
			return notFound(id);
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
	public ResponseEntity<Object> command(@PathVariable String id, @RequestBody CommandRequest req) {
		HostedSession hs = host.get(id);
		if (hs == null)
			return notFound(id);
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

	// open a Server-Sent-Events stream of the session's snapshots. The session thread serializes +
	// offers frames into this connection's bounded queue (dropping the oldest if the client lags);
	// this connection's own virtual thread drains it to the socket until the client disconnects.
	@GetMapping("/{id}/stream")
	public SseEmitter stream(@PathVariable String id) {
		HostedSession hs = host.get(id);
		if (hs == null)
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no session " + id);

		SseEmitter emitter = new SseEmitter(Long.MAX_VALUE); // no timeout — an open-ended feed
		BlockingQueue<String> frames = new ArrayBlockingQueue<>(SSE_QUEUE_CAPACITY);
		// runs on the session thread: serialize + enqueue, dropping the oldest frame if the client
		// has fallen behind, so a slow spectator never blocks the simulation
		AutoCloseable sub = hs.subscribe(snap -> {
			String frame = toJson(snap);
			if (frame != null && !frames.offer(frame)) {
				frames.poll();
				frames.offer(frame);
			}
		});
		Thread drainer = Thread.ofVirtual().name("sse-" + id).start(() -> {
			try {
				while (true) {
					String frame = frames.take();
					emitter.send(SseEmitter.event().data(frame, MediaType.TEXT_PLAIN));
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
				try {
					emitter.complete();
				} catch (RuntimeException ignored) {
					// completion is best-effort
				}
			}
		});
		Runnable cleanup = () -> {
			closeQuietly(sub);
			drainer.interrupt();
		};
		emitter.onCompletion(cleanup);
		emitter.onTimeout(cleanup);
		emitter.onError(t -> cleanup.run());
		return emitter;
	}

	private String toJson(SessionSnapshot snap) {
		try {
			return json.writeValueAsString(snap);
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static void closeQuietly(AutoCloseable sub) {
		try {
			sub.close();
		} catch (Exception ignored) {
			// unsubscribe is best-effort
		}
	}

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

	/** Body of {@code POST /api/sessions/{id}/commands}. */
	public record CommandRequest(String type, String lever, Double rate, Long tick) {
	}
}
