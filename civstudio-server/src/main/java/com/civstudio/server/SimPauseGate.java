package com.civstudio.server;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

/**
 * Pauses the sim while heavy, session-independent work runs — currently on-demand per-province plot
 * generation ({@code docs/plot-serving.md}) — so that work never contends with the lockstep tick
 * loops for CPU. That is the performance guarantee: the sim takes a brief, clean pause instead of
 * jittering, and resumes deterministically.
 * <p>
 * Reference-counted, so overlapping / back-to-back generations pause once and resume once (no
 * pause/resume flicker). Only sessions this gate <em>actually</em> paused (were {@code RUNNING}) are
 * resumed, so a session a user paused stays paused.
 */
@Component
public final class SimPauseGate {

	private final SessionHost host;
	private int active;
	private final Set<HostedSession> pausedByMe = Collections.newSetFromMap(new IdentityHashMap<>());

	public SimPauseGate(SessionHost host) {
		this.host = host;
	}

	/** Run {@code work} with the sim paused (see the class doc). */
	public <T> T underPause(Supplier<T> work) {
		enter();
		try {
			return work.get();
		} finally {
			exit();
		}
	}

	private synchronized void enter() {
		if (active++ == 0)
			for (HostedSession s : host.list())
				if (s.clock() == ClockState.RUNNING) {
					s.pause();
					pausedByMe.add(s);
				}
	}

	private synchronized void exit() {
		if (--active == 0) {
			pausedByMe.forEach(HostedSession::resume);
			pausedByMe.clear();
		}
	}
}
