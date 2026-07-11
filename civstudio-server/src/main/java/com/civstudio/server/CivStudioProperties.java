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
	private final Auth auth = new Auth();

	public Demo getDemo() {
		return demo;
	}

	public Cors getCors() {
		return cors;
	}

	public Auth getAuth() {
		return auth;
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
	 * Authentication / ownership config (see {@code docs/authentication.md}). Phase 1 ships the
	 * ownership plumbing without a login provider yet; {@link #trustDevUserHeader} is the only
	 * knob — a development/test seam for supplying the caller's identity before real login lands.
	 */
	public static class Auth {
		/**
		 * When {@code true}, {@link com.civstudio.server.web.CurrentUserResolver} trusts the
		 * {@code X-CivStudio-User} request header as the caller's user id. This is a
		 * <b>development/test-only</b> fallback used only when there is no logged-in
		 * {@code SecurityContext} (a spoofable header must never be trusted in production) and stays
		 * {@code false} by default.
		 */
		private boolean trustDevUserHeader = false;

		private final Steam steam = new Steam();
		private final Google google = new Google();

		public boolean isTrustDevUserHeader() {
			return trustDevUserHeader;
		}

		public void setTrustDevUserHeader(boolean trustDevUserHeader) {
			this.trustDevUserHeader = trustDevUserHeader;
		}

		public Steam getSteam() {
			return steam;
		}

		public Google getGoogle() {
			return google;
		}

		/**
		 * Google OIDC (a secondary sign-in provider — see {@code docs/authentication.md} phase 3).
		 * Entirely opt-in: the OAuth2 login flow is wired only when {@link #clientId} is set (see
		 * {@code GoogleOAuthConfig}), so the server runs Steam-only by default.
		 */
		public static class Google {
			/** The Google OAuth2 client id (env {@code CIVSTUDIO_AUTH_GOOGLE_CLIENT_ID}); blank = off. */
			private String clientId = "";
			/** The Google OAuth2 client secret (env {@code CIVSTUDIO_AUTH_GOOGLE_CLIENT_SECRET}). */
			private String clientSecret = "";
			/**
			 * The redirect URI registered in the Google console. Defaults to the Spring template
			 * {@code {baseUrl}/login/oauth2/code/google} (correct once forwarded headers are honored —
			 * see {@code application.yml}); override to an absolute URL if needed.
			 */
			private String redirectUri = "{baseUrl}/login/oauth2/code/google";

			public String getClientId() {
				return clientId;
			}

			public void setClientId(String clientId) {
				this.clientId = clientId;
			}

			public String getClientSecret() {
				return clientSecret;
			}

			public void setClientSecret(String clientSecret) {
				this.clientSecret = clientSecret;
			}

			public String getRedirectUri() {
				return redirectUri;
			}

			public void setRedirectUri(String redirectUri) {
				this.redirectUri = redirectUri;
			}
		}

		/** Steam "Sign in through Steam" (OpenID 2.0) config — the default sign-in provider. */
		public static class Steam {
			/**
			 * The OpenID realm = this server's public origin (e.g. {@code
			 * https://dev.civstudio.com}). Optional: when blank the realm is derived from the
			 * request, which is correct for local runs but may be wrong behind a proxy — set it in
			 * production (env {@code CIVSTUDIO_AUTH_STEAM_REALM}).
			 */
			private String realm = "";

			/**
			 * The Steam Web API key used to enrich the persona name / avatar after sign-in
			 * (optional; sign-in works without it — the SteamID is used as the display name).
			 */
			private String apiKey = "";

			public String getRealm() {
				return realm;
			}

			public void setRealm(String realm) {
				this.realm = realm;
			}

			public String getApiKey() {
				return apiKey;
			}

			public void setApiKey(String apiKey) {
				this.apiKey = apiKey;
			}
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
