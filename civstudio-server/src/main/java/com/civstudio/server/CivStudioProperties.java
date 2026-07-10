package com.civstudio.server;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalized server configuration (bound from {@code application.yml} / environment under the
 * {@code civstudio.*} prefix). Replaces the ad-hoc {@code $PORT} / {@code EOS_CORS_ORIGINS} env
 * parsing the old {@code ServerMain}/{@code FeedServer} did by hand.
 */
@ConfigurationProperties("civstudio")
public class CivStudioProperties {

	private final Demo demo = new Demo();
	private final Cors cors = new Cors();

	public Demo getDemo() {
		return demo;
	}

	public Cors getCors() {
		return cors;
	}

	/** The caravan-demo session founded on startup (see {@link DemoSessionSeeder}). */
	public static class Demo {
		/** Whether to found the demo session at boot (the live deployment relies on it). */
		private boolean enabled = true;
		/** The session seed (reproducibility root). */
		private long seed = 7654321L;
		/** The world-map province the demo colony founds into (Dhenijansar). */
		private int provinceId = 4411;
		/** Wall-clock milliseconds per tick — ~one in-game day per second. */
		private long tickRateMillis = 1000L;

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public long getSeed() {
			return seed;
		}

		public void setSeed(long seed) {
			this.seed = seed;
		}

		public int getProvinceId() {
			return provinceId;
		}

		public void setProvinceId(int provinceId) {
			this.provinceId = provinceId;
		}

		public long getTickRateMillis() {
			return tickRateMillis;
		}

		public void setTickRateMillis(long tickRateMillis) {
			this.tickRateMillis = tickRateMillis;
		}
	}

	/**
	 * Cross-origin config: the static map site (Static Web Apps) calls this server (a Container
	 * App) from a different origin. Any {@code localhost} port is additionally allowed for local
	 * development (see {@link WebConfig}). Override via {@code EOS_CORS_ORIGINS} (comma-separated).
	 */
	public static class Cors {
		private List<String> origins = new ArrayList<>(List.of(
				"https://anbennar.civstudio.com", "https://civstudio.com",
				"https://www.civstudio.com"));

		public List<String> getOrigins() {
			return origins;
		}

		public void setOrigins(List<String> origins) {
			this.origins = origins;
		}
	}
}
