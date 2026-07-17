package com.civstudio.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Founds the Phase-A caravan-demo session once the Spring context is ready (the live deployment
 * at {@code dev.civstudio.com} hosts this demo — one standard colony plus six marching
 * caravans). Runs on startup via {@link ApplicationRunner}; the session parameters come from
 * {@link CivStudioProperties.Demo}. A founding failure is logged, not fatal — the server still
 * serves the map bundle and any other sessions.
 */
@Component
public class DemoSessionSeeder implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(DemoSessionSeeder.class);

	private final SessionHost host;
	private final CivStudioProperties props;

	public DemoSessionSeeder(SessionHost host, CivStudioProperties props) {
		this.host = host;
		this.props = props;
	}

	@Override
	public void run(ApplicationArguments args) {
		CivStudioProperties.Demo demo = props.getDemo();
		if (!demo.isEnabled()) {
			log.info("demo session disabled (civstudio.demo.enabled=false)");
			return;
		}
		SessionSpec spec = SessionSpec.caravanDemo(demo.getSeed(), demo.getProvinceId());
		try {
			HostedSession hs;
			try {
				hs = host.create(spec);
			} catch (SessionHost.RunFinishedException over) {
				// The demo's colony collapses — that is the point of it — and a finished run is
				// finished, so a fresh boot would find its own shop window permanently dead. The demo
				// is a FIXTURE, not a record of anyone's play, so it is the one thing allowed to
				// forget itself and start over. A ranked Timeline must never take this path: its
				// verdict is the whole product. (When the public site points at the live Timeline —
				// docs/spectator-lobby.md amendment 3 — this reseeding goes away with the demo.)
				log.info("the demo run {} is over ({}) — forgetting it and dealing a fresh one",
						spec.id(), over.getMessage());
				host.forget(spec.id());
				hs = host.create(spec);
			}
			hs.setTickRateMillis(demo.getTickRateMillis());
			if (hs.state() == HostedSession.State.CREATED)
				hs.start();
			log.info("seeded demo session {} (6 caravans, ~{}ms/tick)", hs.id(),
					demo.getTickRateMillis());
		} catch (RuntimeException e) {
			log.error("failed to seed the demo session", e);
		}
	}
}
