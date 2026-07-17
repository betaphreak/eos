package com.civstudio.server.web;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import tools.jackson.databind.ObjectMapper;

/**
 * One Server-Sent-Events connection, done properly — the transport the session feed and the lobby
 * feed share.
 * <p>
 * <b>A slow client never stalls a producer.</b> Each connection gets its own bounded queue that
 * publishers offer into, dropping the <em>oldest</em> frame if the client has fallen behind, while
 * the connection's own virtual thread drains it to the socket. So the sim thread and a chatter alike
 * hand a frame over and move on, whatever the reader is doing.
 * <p>
 * Extracted from {@code SessionController} when the lobby needed a feed of its own: the queueing,
 * the drain thread, the drop-oldest policy and the unsubscribe-on-disconnect dance are transport,
 * not routing, and there is no version of them worth writing twice.
 */
@Component
public class SseFeed {

	// per-connection buffer depth; a client this far behind loses its oldest frames rather than
	// blocking whoever is producing them
	private static final int QUEUE_CAPACITY = 64;

	private final ObjectMapper json;

	public SseFeed(ObjectMapper json) {
		this.json = json;
	}

	/** Where a publisher hands frames to one connection. Never blocks; never throws at the caller. */
	@FunctionalInterface
	public interface Sink {
		/**
		 * Publish one frame.
		 *
		 * @param event the SSE event name, or {@code null} for the default ("message") event
		 * @param payload the payload, serialized to JSON (a frame that will not serialize is dropped
		 *                rather than sent as a null)
		 */
		void publish(String event, Object payload);
	}

	/**
	 * Open a feed.
	 *
	 * @param name     a thread name for the drainer, so a stuck connection is identifiable in a dump
	 * @param finished when this reads {@code true} and the queue is drained, the stream ends rather
	 *                 than holding an async request open forever (a finished run). {@code null} for
	 *                 an open-ended feed with no natural end — the lobby.
	 * @param wire     called once with this connection's sink; returns the subscriptions to close
	 *                 when the client goes away
	 * @return the emitter to return from a controller
	 */
	public SseEmitter open(String name, BooleanSupplier finished, Function<Sink, List<AutoCloseable>> wire) {
		SseEmitter emitter = new SseEmitter(Long.MAX_VALUE); // no timeout — an open-ended feed
		BlockingQueue<Frame> frames = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
		List<AutoCloseable> subs = wire.apply((event, payload) -> offer(frames, event, payload));

		Thread drainer = Thread.ofVirtual().name("sse-" + name).start(() -> {
			try {
				// Open the connection before there is anything to say. Spring does not flush the
				// response headers until the first event, so a feed with nothing to send yet — a
				// lobby whose chat is quiet — leaves the client waiting on headers that never come:
				// EventSource never fires onopen, never evaluates CORS, and the first real message
				// arrives to nobody. A comment is a legal SSE frame that no client mistakes for data.
				emitter.send(SseEmitter.event().comment("open"));
				while (true) {
					Frame f = frames.take();
					SseEmitter.SseEventBuilder ev = SseEmitter.event().data(f.data(), MediaType.TEXT_PLAIN);
					if (f.event() != null)
						ev.name(f.event());
					emitter.send(ev);
					if (finished != null && finished.getAsBoolean() && frames.isEmpty())
						break; // the last frame of a finished feed is flushed — let the request go
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (Exception disconnected) {
				// client closed the tab / emitter completed — fall through to unsubscribe
			} finally {
				closeAll(subs);
				try {
					emitter.complete();
				} catch (RuntimeException ignored) {
					// completion is best-effort
				}
			}
		});
		Runnable cleanup = () -> {
			closeAll(subs);
			drainer.interrupt();
		};
		emitter.onCompletion(cleanup);
		emitter.onTimeout(cleanup);
		emitter.onError(t -> cleanup.run());
		return emitter;
	}

	// drop-oldest enqueue: never block the producer on a slow client
	private void offer(BlockingQueue<Frame> q, String event, Object payload) {
		String data;
		try {
			data = json.writeValueAsString(payload);
		} catch (RuntimeException e) {
			return; // a serialization failure — skip this frame rather than enqueue a null
		}
		Frame f = new Frame(event, data);
		if (!q.offer(f)) {
			q.poll();
			q.offer(f);
		}
	}

	private static void closeAll(List<AutoCloseable> subs) {
		for (AutoCloseable sub : subs)
			try {
				sub.close();
			} catch (Exception ignored) {
				// unsubscribing is best-effort
			}
	}

	// one SSE frame: the serialized payload plus its event name
	private record Frame(String event, String data) {
	}
}
