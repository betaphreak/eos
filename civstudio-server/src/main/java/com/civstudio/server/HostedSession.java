package com.civstudio.server;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.CopyOnWriteArrayList;

import com.civstudio.agent.Caravan;
import com.civstudio.io.SimLog;
import com.civstudio.server.command.CommandLog;
import com.civstudio.server.command.CommandStore;
import com.civstudio.server.command.GameCommand;
import com.civstudio.server.chat.ChatStore;
import com.civstudio.server.render.ChatMessage;
import com.civstudio.server.render.LogLine;
import com.civstudio.server.render.SessionEventLog;
import com.civstudio.server.render.SessionLogBuffer;
import com.civstudio.server.render.SessionSnapshot;
import com.civstudio.server.render.Snapshots;
import com.civstudio.settlement.GameSession;
import com.civstudio.settlement.Settlement;
import com.civstudio.util.Rng;

import lombok.extern.java.Log;

/**
 * One authoritative, hosted run of a {@link GameSession} — the server-side unit a browser
 * spectator subscribes to (see {@code docs/client-server.md}, Phase A). The host owns the
 * <b>tick authority</b>: a single loop, on its own (virtual) thread, that advances the
 * session one in-game day per tick, applies any due {@linkplain CommandLog commands} at the
 * deterministic <em>top of the tick</em>, and emits a render {@link SessionSnapshot} to its
 * subscribers.
 * <p>
 * <b>Determinism.</b> The colonies are advanced <em>sequentially</em> in lockstep (each
 * colony's whole day, in turn). Colonies never read one another mid-day (they draw from
 * disjoint RNG/name/mortality partitions — see {@code GameSession}), so this is
 * bit-identical to the concurrent lockstep of {@link
 * com.civstudio.simulation.SessionRunner}, while giving the host trivial, race-free control
 * over pacing. Pause/resume/step/rate affect only <em>wall-clock timing</em>, never sim
 * results; only commands change state, and they are tick-stamped, so any two replays of the
 * same spec + command log produce identical state.
 * <p>
 * <b>Snapshots are assembled on this thread</b> (in {@link #emit()}, between ticks) and
 * cached; a subscriber — including a late joiner — receives the cached last snapshot, so
 * the projection never races {@code newDay}, and a slow subscriber never stalls the sim
 * (the transport is expected to hand off to its own buffer).
 */
@Log
public final class HostedSession {

	/** The host's control state. */
	public enum State {
		/** Built, thread not started. */
		CREATED,
		/** Ticking freely (paced by the tick rate). */
		RUNNING,
		/** Halted; advances only by {@link HostedSession#step(int)}. */
		PAUSED,
		/**
		 * Stopped from <em>outside</em> — an admin {@link HostedSession#stop()} or server
		 * shutdown; the thread has exited. The run did not reach its own end, so a client may
		 * reasonably keep watching for it to come back (e.g. across a redeploy).
		 */
		STOPPED,
		/**
		 * The run <em>ended itself</em> — game over. The thread has exited and this session will
		 * never tick again, so a client must stop reconnecting and show its terminal screen (the
		 * reason is in {@link HostedSession#endReason()}). Distinct from {@link #STOPPED} precisely
		 * so a finished run is not mistaken for an idle one; see {@code docs/game-over.md}.
		 */
		GAME_OVER
	}

	private final String id;
	// the app_user id that owns this run, or null for an unowned (public) session such as the
	// server-seeded demo. Write commands/control are gated on this (see SessionController); an
	// unowned session stays open to anyone, an owned one only to its owner. Phase 1 of the auth
	// work (docs/authentication.md) — real owners arrive with login in Phase 2.
	private final String owner;
	private final SessionSpec spec;
	private final GameSession session;
	private final List<Settlement> colonies;
	private final CommandLog commandLog = new CommandLog();
	private final CommandStore commandStore;
	private final ChatStore chatStore;
	private final List<Consumer<SessionSnapshot>> subscribers = new CopyOnWriteArrayList<>();

	// the authoritative tick — in-game days elapsed; volatile so control/HTTP threads read
	// a fresh value while the session thread advances it
	private volatile long tick = 0;

	// The run's clock origin: the colonies' founding date. The session's date is derived from this
	// plus the tick above (see date()) — it is NOT scavenged from whichever colonies are still
	// alive, which is what let the last colony's death freeze every wandering band with it.
	// See docs/spectator-lobby.md §Phase 0.
	private final LocalDate startDate;

	private volatile State state = State.CREATED;

	// why the run ended, once it has (null until then) — display text for the client's terminal
	// screen. Set on the session thread before the state flips to GAME_OVER; volatile so an HTTP
	// thread that sees GAME_OVER also sees the reason that was written before it.
	private volatile String endReason;

	// wall-clock milliseconds per tick when RUNNING (0 = as fast as possible); a live knob
	// (the server ticks ~one in-game day per second by default — see ServerMain)
	private volatile long tickRateMillis = 1000;

	// emit a snapshot every N ticks (1 = every day); a throttle for high tick rates
	private volatile int snapshotEveryTicks = 1;

	// the last snapshot assembled on the session thread — handed to late subscribers so the
	// projection is never built off-thread (which would race newDay's agent-set mutation)
	private volatile SessionSnapshot lastSnapshot;

	// holds the session's event-log lines between emits; a SimLog tap fills it on the colony
	// threads, emit() drains it into each snapshot (the browser's live log bar). See run()/emit().
	private final SessionLogBuffer logBuffer = new SessionLogBuffer();

	// a retained rolling tail of the same lines (fed by the same tap, but never drained), so the
	// get_events tool / events MCP resource can serve real history rather than the per-frame delta.
	private final SessionEventLog eventLog = new SessionEventLog();

	// lobby chat: an immediate broadcast channel, separate from the tick-paced snapshot feed (so
	// messages don't wait up to a tick). Persistence + the replay backlog live in the ChatStore;
	// chatLock serializes a subscribe (replay + add) against a post (append + broadcast) so no
	// message is missed or double-delivered.
	private static final int CHAT_REPLAY = 40;   // messages replayed to a newly-connected spectator
	private final Object chatLock = new Object();
	private final java.util.List<Consumer<ChatMessage>> chatSubscribers = new java.util.ArrayList<>();

	// pause/step coordination: the session thread waits on `gate` while PAUSED with no step
	// credit; control threads mutate stepCredits/state under `gate` and notify
	private final Object gate = new Object();
	private int stepCredits = 0;

	private Thread thread;

	/**
	 * Build a hosted session over an already-founded (not yet started) session and its
	 * colonies. The {@link SessionHost} founds these from a {@link SessionSpec} and assigns the
	 * surrogate id and owner (the id keys the {@link SessionHost} registry and the command log;
	 * the owner gates writes — see {@code docs/authentication.md}).
	 *
	 * @param id       the session's surrogate id (registry + command-log key)
	 * @param owner    the owning {@code app_user} id, or {@code null} for an unowned/public
	 *                 session (the server-seeded demo)
	 * @param spec     the founding spec (seed/scenario/province — the determinism root)
	 * @param session  the game session (owns the seed, world map and wandering bands)
	 * @param colonies the session's colonies (advanced in lockstep each tick)
	 */
	public HostedSession(String id, String owner, SessionSpec spec, GameSession session,
			List<Settlement> colonies, CommandStore commandStore, ChatStore chatStore) {
		this.id = id;
		this.owner = owner;
		this.spec = spec;
		this.session = session;
		this.colonies = List.copyOf(colonies);
		this.commandStore = commandStore;
		this.chatStore = chatStore;
		// the clock origin: the colonies are founded together, on the same day, so any of them
		// carries it. Read once here rather than per tick — a colony's start date never moves, and
		// reading it from a colony LATER would reintroduce the very dependency this removes.
		this.startDate = this.colonies.isEmpty() ? LocalDate.EPOCH : this.colonies.get(0).getStartDate();
	}

	/** Start ticking freely (paced by the tick rate). */
	public void start() {
		launch(State.RUNNING);
	}

	/** Start halted — advance only via {@link #step(int)} (used by deterministic tests). */
	public void startPaused() {
		launch(State.PAUSED);
	}

	private synchronized void launch(State initial) {
		if (thread != null)
			throw new IllegalStateException("session " + id + " already started");
		state = initial;
		thread = Thread.ofVirtual().name("session-" + id).start(this::run);
	}

	/** Pause a running session (no-op if not RUNNING). */
	public void pause() {
		synchronized (gate) {
			if (state == State.RUNNING)
				state = State.PAUSED;
			gate.notifyAll();
		}
	}

	/** Resume a paused session (no-op if not PAUSED). */
	public void resume() {
		synchronized (gate) {
			if (state == State.PAUSED) {
				state = State.RUNNING;
				gate.notifyAll();
			}
		}
	}

	/**
	 * Advance a paused session by {@code n} ticks, then pause again — deterministic
	 * single-stepping. A no-op unless PAUSED.
	 *
	 * @param n the number of ticks to advance
	 */
	public void step(int n) {
		if (n <= 0)
			return;
		synchronized (gate) {
			if (state == State.PAUSED) {
				stepCredits += n;
				gate.notifyAll();
			}
		}
	}

	/** Stop the session for good and wait for its thread to finish. */
	public void stop() {
		Thread t;
		synchronized (gate) {
			// never downgrade a finished run to "stopped from outside": a GAME_OVER session that an
			// admin later stops (or that shutdown sweeps) is still a finished run, and its terminal
			// state is the record of that.
			if (state != State.GAME_OVER)
				state = State.STOPPED;
			gate.notifyAll();
			t = thread;
		}
		if (t != null) {
			t.interrupt(); // break a rate-limit sleep
			try {
				t.join();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	/** Set the wall-clock milliseconds per tick when running (0 = uncapped). */
	public void setTickRateMillis(long millis) {
		this.tickRateMillis = Math.max(0, millis);
	}

	/** Set the snapshot cadence (emit every {@code n} ticks; clamped to at least 1). */
	public void setSnapshotEveryTicks(int n) {
		this.snapshotEveryTicks = Math.max(1, n);
	}

	/**
	 * Submit a {@linkplain GameCommand command} to this session's log — the interactive
	 * seam (empty during spectator play). Applied at the top of its tick on the session
	 * thread.
	 *
	 * @param command the command to enqueue
	 */
	public void submit(GameCommand command) {
		commandStore.append(id, command); // durable first, so a restart can replay it
		commandLog.append(command);
	}

	/**
	 * Append a persisted command to the log without re-persisting it — the resume path. Called
	 * by {@link SessionHost} when it re-founds a session, so the loaded command log replays at
	 * its recorded ticks as the session advances (state = f(spec, command log)).
	 *
	 * @param command a command loaded from the {@link CommandStore}
	 */
	public void replay(GameCommand command) {
		commandLog.append(command);
	}

	/**
	 * Subscribe to this session's snapshot feed. The subscriber is immediately handed the
	 * current cached snapshot (if any), then every subsequent emission. Returns a handle
	 * that unsubscribes when closed. The subscriber runs on the session thread, so it must
	 * not block (a transport hands the snapshot to its own buffer).
	 *
	 * @param subscriber the snapshot consumer
	 * @return an unsubscribe handle
	 */
	public AutoCloseable subscribe(Consumer<SessionSnapshot> subscriber) {
		subscribers.add(subscriber);
		SessionSnapshot snap = lastSnapshot;
		if (snap != null)
			subscriber.accept(snap);
		return () -> subscribers.remove(subscriber);
	}

	/**
	 * Post a lobby chat message: append it to the replay backlog and broadcast it immediately to
	 * every chat subscriber. Called off the session thread (an HTTP request thread); the subscribers'
	 * consumers must not block (each hands the message to its own SSE buffer).
	 *
	 * @param user the poster's display name (server-resolved, not client-supplied)
	 * @param text the message body
	 */
	public void postChat(String user, String text) {
		ChatMessage msg = new ChatMessage(user, text);
		synchronized (chatLock) {
			chatStore.append(id, user, text);   // persist (durable when a datasource is configured)
			for (Consumer<ChatMessage> c : chatSubscribers)
				try {
					c.accept(msg);
				} catch (RuntimeException e) {
					// a slow/broken chat subscriber must never break the broadcast
				}
		}
	}

	/**
	 * Subscribe to this session's chat channel. The current backlog is replayed to {@code sub} on
	 * subscribe (so a late joiner sees recent messages), then every subsequent post. Returns an
	 * unsubscribe handle.
	 *
	 * @param sub the chat-message consumer
	 * @return an unsubscribe handle
	 */
	public AutoCloseable subscribeChat(Consumer<ChatMessage> sub) {
		synchronized (chatLock) {
			for (ChatMessage m : chatStore.recent(id, CHAT_REPLAY))
				try {
					sub.accept(m);
				} catch (RuntimeException ignored) {
					// best-effort backlog replay
				}
			chatSubscribers.add(sub);
		}
		return () -> {
			synchronized (chatLock) {
				chatSubscribers.remove(sub);
			}
		};
	}

	/** This session's id. */
	public String id() {
		return id;
	}

	/**
	 * Why the run ended, as display text for a client's game-over screen ({@code "Dhenijansar
	 * departed as a Caravan on 1452-03-02"}), or {@code null} while it has not ended itself. Only
	 * ever set alongside {@link State#GAME_OVER} — a session stopped from outside has no reason,
	 * because it did not reach an end.
	 *
	 * @return the end reason, or {@code null}
	 */
	public String endReason() {
		return endReason;
	}

	/**
	 * Whether the run is over for good — {@link State#STOPPED stopped from outside} or {@link
	 * State#GAME_OVER game over}. Its thread has exited either way, so it will never tick again.
	 * <p>
	 * Ask this rather than comparing to a single terminal state: the two differ in <em>why</em> the
	 * run ended (and so in what a client should do about it), never in whether it is running.
	 *
	 * @return {@code true} if this session will never tick again
	 */
	public boolean isTerminal() {
		return state == State.STOPPED || state == State.GAME_OVER;
	}

	/**
	 * The session's in-game date — its {@linkplain #tick() tick} counted forward from the run's
	 * founding date. <b>The session's own clock:</b> it does not ask the colonies what day it is,
	 * so it keeps time even when none of them is left to answer.
	 * <p>
	 * Identical to a living colony's {@code getDate()} — both are {@code startDate + steps}, and a
	 * live colony steps exactly once per tick. The difference only shows once nothing is alive: the
	 * colonies' dates freeze where they died, while the session's clock is free to run on (which is
	 * what lets wandering bands outlive their colony). See {@code docs/spectator-lobby.md} §Phase 0.
	 *
	 * @return the session's current in-game date
	 */
	public LocalDate date() {
		return startDate.plusDays(tick);
	}

	/**
	 * The owning {@code app_user} id, or {@code null} if this is an unowned/public session
	 * (the server-seeded demo). Control/command writes are gated on this — an unowned session
	 * is open to anyone, an owned one only to its owner (see {@code docs/authentication.md}).
	 */
	public String owner() {
		return owner;
	}

	/** This session's founding spec. */
	public SessionSpec spec() {
		return spec;
	}

	/** The authoritative tick (in-game days elapsed). */
	public long tick() {
		return tick;
	}

	/** The current control state. */
	public State state() {
		return state;
	}

	/** The underlying game session (seed, world map, wandering bands). */
	public GameSession session() {
		return session;
	}

	/** The session's colonies. */
	public List<Settlement> colonies() {
		return colonies;
	}

	/** The command log (the session's ordered input/replay history). */
	public CommandLog commandLog() {
		return commandLog;
	}

	/** The last emitted snapshot, or {@code null} before the first emission. */
	public SessionSnapshot currentSnapshot() {
		return lastSnapshot;
	}

	/**
	 * The retained tail of this session's event log (foundings, deaths, policy changes, …), filtered
	 * by minimum severity, in-game date range and substring. Unlike {@link #currentSnapshot()}'s
	 * per-frame log delta this is a rolling window of history — the read seam for the {@code
	 * get_events} MCP tool / {@code events} resource. Read-only; safe on any thread.
	 *
	 * @see com.civstudio.server.render.SessionEventLog#query
	 */
	public List<LogLine> eventTail(String level, String from, String to, String grep, int limit) {
		return eventLog.query(level, from, to, grep, limit);
	}

	// the session thread's body: start the colonies, then tick until they are all done or
	// the session is stopped, applying commands and emitting snapshots along the way
	private void run() {
		// records emitted on this thread carry (and route to) this session's colony/log
		SimLog.bind(colonies.get(0));
		// tap this session's log into the snapshot feed before founding, so the "was founded"
		// lines are captured; closed in the finally below when the session tears down
		AutoCloseable logTap = SimLog.tap(colonies.get(0), e -> {
			logBuffer.add(e.date(), e.message(), e.level(), e.rank());
			eventLog.add(e.date(), e.message(), e.level(), e.rank());
		});
		for (Settlement c : colonies)
			c.start();
		emit(); // tick-0 snapshot, so a subscriber (even to a paused session) sees state
		// which of the two exits fired: the run reaching its own end (game over) or a stop() from
		// outside. Knowable here and nowhere else — the finally cannot tell them apart after the fact.
		boolean endedItself = false;
		try {
			while (true) {
				if (!awaitTickPermit())
					break; // stopped from outside
				if (allDead()) {
					endedItself = true; // the run reached its own end — game over
					break;
				}
				long entering = tick;
				// deterministic top-of-tick: apply the commands due for this tick
				for (GameCommand cmd : commandLog.drainDueBy(entering))
					cmd.apply(this);
				advanceOneDay();
				tick++;
				if (tick % snapshotEveryTicks == 0)
					emit();
			}
		} finally {
			for (Settlement c : colonies) {
				try {
					c.finishRun();
				} catch (RuntimeException e) {
					// finalization is best-effort; a botched teardown must not wedge the host
				}
			}
			// the reason is read AFTER finishRun: that is where a dissolving colony actually
			// departs as its band (SettlementLifecycle.finishRun), so before it there is nothing
			// to tell "died" from "departed as a Caravan".
			if (endedItself) {
				endReason = describeEnd();
				state = State.GAME_OVER;
			} else {
				state = State.STOPPED;
			}
			emit(); // final snapshot so clients see the terminal state
			try {
				logTap.close();
			} catch (Exception ignored) {
				// unregistering the tap is best-effort
			}
			SimLog.closeSession(colonies.get(0));
		}
	}

	// advance every live colony one in-game day (sequential lockstep), then the session's
	// wandering bands — the marching six of the demo (see docs/caravan-march.md)
	private void advanceOneDay() {
		for (Settlement c : colonies)
			if (!c.isDead())
				c.newDay();
		// The day the colonies just stepped INTO. `tick` is still the day we entered — the loop
		// increments it after this returns — so the day now in progress is tick + 1.
		//
		// This used to be the max date over the LIVE colonies, which meant the bands' clock died
		// with the last colony (tickBands no-ops on a null date), stranding the very band a
		// dissolving colony had just departed as. The session's own clock has no such gap.
		// Identical while any colony lives: both are startDate + steps. See docs/spectator-lobby.md.
		tickBands(startDate.plusDays(tick + 1));
	}

	// advance the session's wandering bands one day on the session band RNG — single-
	// threaded here (the whole session ticks on this one thread), so it is deterministic;
	// labelled (realm) since a band belongs to no one colony. Draws nothing with no bands.
	private void tickBands(LocalDate date) {
		List<Caravan> bands = session.getCaravans();
		if (bands.isEmpty())
			return;
		// NB no null-date guard any more: the date is the session's own clock, which always knows
		// what day it is. It was that guard — reached whenever no colony was left to date the day —
		// that froze the bands the moment their colony died.
		final LocalDate d = date;
		SimLog.asRealm(colonies.get(0), () -> {
			Rng rng = session.getBandRng();
			for (Caravan band : bands)
				band.tick(d, rng);
			// bury the day's dead before the snapshot is built, so a band that starved out
			// stops being drawn as a live marker with a head-count of zero
			for (Caravan dead : session.pruneSpentCaravans())
				log.info("A band under " + dead.getLeader()
						+ " starved out on the road and was lost, on " + d + ".");
		});
	}

	// block while paused with no step credit; consume one credit per stepped tick; pace a
	// freely-running session by the tick rate. Returns false once the session is stopped.
	private boolean awaitTickPermit() {
		synchronized (gate) {
			while (state == State.PAUSED && stepCredits == 0)
				try {
					gate.wait();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return false;
				}
			if (state == State.STOPPED)
				return false;
			if (state == State.PAUSED && stepCredits > 0)
				stepCredits--; // spend one step; loop back to PAUSED after this tick
		}
		// pace only a freely-running session (a single-step advances immediately)
		if (state == State.RUNNING && tickRateMillis > 0)
			try {
				Thread.sleep(tickRateMillis);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		return state != State.STOPPED;
	}

	private boolean allDead() {
		for (Settlement c : colonies)
			if (!c.isDead())
				return false;
		return true;
	}

	// Why this run ended, in the words a player should read. Called from the finally, after
	// finishRun, so a dissolved colony has its band by now. The engine already distinguishes the two
	// endings (SettlementLifecycle: a colony that crossed the workforce floor departs as a
	// SettlerCaravan; one that simply ran out of laborers does not), so this reads that state rather
	// than re-deriving the rule. Multi-colony sessions report each colony's fate — a Timeline's
	// "won by <name>" belongs here later (docs/game-over.md §Amendment).
	private String describeEnd() {
		List<String> fates = new ArrayList<>();
		for (Settlement c : colonies) {
			String when = c.getDeathDate() == null ? "" : " on " + c.getDeathDate();
			fates.add(c.getDepartedBand() != null
					? c.getName() + " departed as a Caravan" + when
					: c.getName() + " died" + when);
		}
		return String.join("; ", fates);
	}

	// assemble a fresh snapshot on this (session) thread and push it to subscribers; cache
	// it for late joiners. A misbehaving subscriber is dropped rather than allowed to wedge
	// the loop.
	private void emit() {
		SessionSnapshot snap = Snapshots.of(id, spec.seed(), spec.scenario(),
				state.name(), endReason, tick, date(), colonies, session.getWorldMap(), session.getCaravans(),
				logBuffer.drain());
		lastSnapshot = snap;
		for (Consumer<SessionSnapshot> s : subscribers) {
			try {
				s.accept(snap);
			} catch (RuntimeException e) {
				subscribers.remove(s);
			}
		}
	}
}
