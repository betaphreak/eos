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
	private final Anbennar anbennar = new Anbennar();
	private final Plots plots = new Plots();

	public Demo getDemo() {
		return demo;
	}

	public Plots getPlots() {
		return plots;
	}

	public Cors getCors() {
		return cors;
	}

	public Auth getAuth() {
		return auth;
	}

	public Anbennar getAnbennar() {
		return anbennar;
	}

	/**
	 * The canonical upstream source of the Anbennar EU4 mod files (map rasters + Clausewitz
	 * metadata) that the engine reads on demand — the sim's runtime plot generation
	 * ({@code ProvinceRaster}) and the dev-time exporters. These files are <b>not</b> vendored in
	 * the repo: they are downloaded individually, when needed, from this source (pinned to
	 * {@link #ref}) and cached under {@link #cacheDir}. The engine owns the fetch/cache
	 * ({@code com.civstudio.data.AnbennarFiles}); this Spring config is pushed into it at server
	 * startup so a deployment can override the source. See {@code docs/anbennar-files.md} and the
	 * {@code anbennar-canonical-source} note.
	 */
	public static class Anbennar {
		/** The mod's canonical git host (GitLab, not GitHub). Raw files are fetched from here. */
		private String baseUrl = "https://gitlab.com/anbennar/anbennar-eu4-dev";
		/**
		 * Overrides the committed source lock (the SHA the derived {@code map/*.json} resources were
		 * built from) for ad-hoc/testing fetches. Normally left at this default and bumped via the
		 * refresh workflow, not per-deployment — runtime fetch must match the committed resources, so
		 * changing only this without regenerating them yields an inconsistent world. The mod tracks
		 * {@code new-master}; this is a specific locked tip. See {@code docs/anbennar-files.md}
		 * §Staying current with upstream.
		 */
		private String ref = "";
		/**
		 * Local on-disk cache root for downloaded files. Files are stored under
		 * {@code <cacheDir>/<ref>/<mod-relative-path>} so bumping {@link #ref} naturally invalidates
		 * the cache. Defaults to a gitignored working-dir folder for local dev; the deployment points
		 * it at a <b>mounted volume</b> (env {@code ANBENNAR_CACHE_DIR}) so downloads persist across
		 * container restarts/replicas. See {@code docs/anbennar-files.md} §Runtime.
		 */
		private String cacheDir = ".anbennar-cache";

		/**
		 * Optional GitLab access token (env {@code ANBENNAR_TOKEN}, a deployment secret — never
		 * committed). Sent as the {@code PRIVATE-TOKEN} header on fetches + tree listings to use the
		 * authenticated rate limit instead of the anonymous one. Blank by default: the mod repo is
		 * public, so anonymous fetch works but is rate-limited (matters for the directory-walking
		 * exporters). A read-only scope ({@code read_api} / {@code read_repository}) is enough.
		 */
		private String token = "";

		public String getBaseUrl() {
			return baseUrl;
		}

		public void setBaseUrl(String baseUrl) {
			this.baseUrl = baseUrl;
		}

		public String getRef() {
			return ref;
		}

		public void setRef(String ref) {
			this.ref = ref;
		}

		public String getCacheDir() {
			return cacheDir;
		}

		public void setCacheDir(String cacheDir) {
			this.cacheDir = cacheDir;
		}

		public String getToken() {
			return token;
		}

		public void setToken(String token) {
			this.token = token;
		}
	}

	/**
	 * Per-province plot serving (see {@code docs/plot-serving.md}). The server generates a
	 * province's plot grid on demand ({@code GET /api/plots/{id}}), pausing the sim while it does
	 * so, and caches the gzipped result under {@link #cacheDir} (+ an in-memory LRU of
	 * {@link #lruSize} provinces). Generation is the (once-per-province) expensive step; serving
	 * from the cache is trivial.
	 */
	public static class Plots {
		/**
		 * Local on-disk cache root for generated plot grids ({@code <cacheDir>/<id>.json.gz}).
		 * Defaults to a gitignored working-dir folder for local dev; the deployment points it at a
		 * <b>persistent mounted volume</b> (env {@code PLOT_CACHE_DIR}) so a province is generated
		 * once ever, not once per container restart.
		 */
		private String cacheDir = ".plot-cache";
		/** How many provinces' gz blobs to keep hot in memory (an LRU over the disk cache). */
		private int lruSize = 512;

		public String getCacheDir() {
			return cacheDir;
		}

		public void setCacheDir(String cacheDir) {
			this.cacheDir = cacheDir;
		}

		public int getLruSize() {
			return lruSize;
		}

		public void setLruSize(int lruSize) {
			this.lruSize = lruSize;
		}
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

		/**
		 * Operator allow-list (env {@code CIVSTUDIO_AUTH_ADMINS}, comma-separated). An entry may be
		 * a user's provider subject (a SteamID64, a Google {@code sub}), a {@code provider:subject}
		 * pair, an OIDC email, or an {@code app_user} id — a signed-in user matching any form is
		 * granted {@code ROLE_ADMIN} and may control/command <em>any</em> session (bypassing
		 * ownership). Matched case-insensitively. See {@code docs/authentication.md}.
		 */
		private List<String> admins = new ArrayList<>();

		private final Steam steam = new Steam();
		private final Google google = new Google();

		public boolean isTrustDevUserHeader() {
			return trustDevUserHeader;
		}

		public void setTrustDevUserHeader(boolean trustDevUserHeader) {
			this.trustDevUserHeader = trustDevUserHeader;
		}

		public List<String> getAdmins() {
			return admins;
		}

		public void setAdmins(List<String> admins) {
			this.admins = admins;
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
			/**
			 * Master switch for Google sign-in (env {@code CIVSTUDIO_AUTH_GOOGLE_ENABLED=true}). A
			 * dedicated dash-free flag rather than gating on {@code client-id} directly, because
			 * {@code @ConditionalOnProperty} does not reliably match a kebab-case property against the
			 * underscore env-var form ({@code CIVSTUDIO_AUTH_GOOGLE_CLIENT_ID}). Set this plus the
			 * client id/secret to turn Google on.
			 */
			private boolean enabled = false;
			/** The Google OAuth2 client id (env {@code CIVSTUDIO_AUTH_GOOGLE_CLIENT_ID}). */
			private String clientId = "";
			/** The Google OAuth2 client secret (env {@code CIVSTUDIO_AUTH_GOOGLE_CLIENT_SECRET}). */
			private String clientSecret = "";
			/**
			 * The redirect URI registered in the Google console. Defaults to the Spring template
			 * {@code {baseUrl}/login/oauth2/code/google} (correct once forwarded headers are honored —
			 * see {@code application.yml}); override to an absolute URL if needed.
			 */
			private String redirectUri = "{baseUrl}/login/oauth2/code/google";

			public boolean isEnabled() {
				return enabled;
			}

			public void setEnabled(boolean enabled) {
				this.enabled = enabled;
			}

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
