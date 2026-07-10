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
		try {
			HostedSession hs = host.create(SessionSpec.caravanDemo(demo.getSeed(), demo.getProvinceId()));
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
