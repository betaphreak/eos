package com.civstudio.server;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.CopyOnWriteArrayList;

import com.civstudio.agent.Caravan;
import com.civstudio.io.SimLog;
import com.civstudio.server.command.CommandLog;
import com.civstudio.server.command.CommandStore;
import com.civstudio.server.command.GameCommand;
import com.civstudio.server.render.SessionSnapshot;
import com.civstudio.server.render.Snapshots;
import com.civstudio.settlement.GameSession;
import com.civstudio.settlement.Settlement;
import com.civstudio.util.Rng;

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
public final class HostedSession {

	/** The host's control state. */
	public enum State {
		/** Built, thread not started. */
		CREATED,
		/** Ticking freely (paced by the tick rate). */
		RUNNING,
		/** Halted; advances only by {@link HostedSession#step(int)}. */
		PAUSED,
		/** Finished (colonies done or stopped); the thread has exited. */
		STOPPED
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
	private final List<Consumer<SessionSnapshot>> subscribers = new CopyOnWriteArrayList<>();

	// the authoritative tick — in-game days elapsed; volatile so control/HTTP threads read
	// a fresh value while the session thread advances it
	private volatile long tick = 0;

	private volatile State state = State.CREATED;

	// wall-clock milliseconds per tick when RUNNING (0 = as fast as possible); a live knob
	// (the server ticks ~one in-game day per second by default — see ServerMain)
	private volatile long tickRateMillis = 1000;

	// emit a snapshot every N ticks (1 = every day); a throttle for high tick rates
	private volatile int snapshotEveryTicks = 1;

	// the last snapshot assembled on the session thread — handed to late subscribers so the
	// projection is never built off-thread (which would race newDay's agent-set mutation)
	private volatile SessionSnapshot lastSnapshot;

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
			List<Settlement> colonies, CommandStore commandStore) {
		this.id = id;
		this.owner = owner;
		this.spec = spec;
		this.session = session;
		this.colonies = List.copyOf(colonies);
		this.commandStore = commandStore;
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

	/** This session's id. */
	public String id() {
		return id;
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

	// the session thread's body: start the colonies, then tick until they are all done or
	// the session is stopped, applying commands and emitting snapshots along the way
	private void run() {
		// records emitted on this thread carry (and route to) this session's colony/log
		SimLog.bind(colonies.get(0));
		for (Settlement c : colonies)
			c.start();
		emit(); // tick-0 snapshot, so a subscriber (even to a paused session) sees state
		try {
			while (true) {
				if (!awaitTickPermit())
					break; // stopped
				if (allDead())
					break;
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
			state = State.STOPPED;
			emit(); // final snapshot so clients see the terminal state
			SimLog.closeSession(colonies.get(0));
		}
	}

	// advance every live colony one in-game day (sequential lockstep), then the session's
	// wandering bands — the marching six of the demo (see docs/caravan-march.md)
	private void advanceOneDay() {
		LocalDate date = null;
		for (Settlement c : colonies)
			if (!c.isDead()) {
				c.newDay();
				if (date == null || c.getDate().isAfter(date))
					date = c.getDate();
			}
		tickBands(date);
	}

	// advance the session's wandering bands one day on the session band RNG — single-
	// threaded here (the whole session ticks on this one thread), so it is deterministic;
	// labelled (realm) since a band belongs to no one colony. Draws nothing with no bands.
	private void tickBands(LocalDate date) {
		List<Caravan> bands = session.getCaravans();
		if (bands.isEmpty() || date == null)
			return;
		final LocalDate d = date;
		SimLog.asRealm(colonies.get(0), () -> {
			Rng rng = session.getBandRng();
			for (Caravan band : bands)
				band.tick(d, rng);
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

	// assemble a fresh snapshot on this (session) thread and push it to subscribers; cache
	// it for late joiners. A misbehaving subscriber is dropped rather than allowed to wedge
	// the loop.
	private void emit() {
		SessionSnapshot snap = Snapshots.of(id, spec.seed(), spec.scenario(),
				state.name(), tick, colonies, session.getWorldMap(), session.getCaravans());
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
