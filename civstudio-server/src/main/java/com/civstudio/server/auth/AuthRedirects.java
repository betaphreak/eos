package com.civstudio.server.auth;

import java.net.URI;
import java.util.Set;

/**
 * Shared post-login redirect handling for every provider (Steam and OIDC — see
 * {@code docs/authentication.md}). A login carries a "where to return the browser" target through
 * the round trip; on the way back it must be validated so login can't be turned into an
 * open-redirect. A target is trusted only if it is a same-site relative path, a configured site
 * origin, or localhost (dev); anything else falls back to the site root.
 */
public final class AuthRedirects {

	/** Session attribute the OIDC flow stashes its validated return target under. */
	public static final String SESSION_TARGET_ATTR = "civstudio.postLoginRedirect";

	private AuthRedirects() {
	}

	/**
	 * Validate a requested post-login redirect target.
	 *
	 * @param target         the requested target (may be {@code null})
	 * @param allowedOrigins the trusted site origins (the CORS origins)
	 * @return {@code target} if trusted, else {@code "/"}
	 */
	public static String safe(String target, Set<String> allowedOrigins) {
		if (target == null || target.isBlank())
			return "/";
		if (target.startsWith("/") && !target.startsWith("//"))
			return target; // a same-site relative path
		try {
			URI uri = URI.create(target);
			String host = uri.getHost();
			String origin = uri.getScheme() + "://" + uri.getAuthority();
			if (allowedOrigins.contains(origin) || "localhost".equals(host) || "127.0.0.1".equals(host))
				return target;
		} catch (RuntimeException malformed) {
			// fall through to the safe default
		}
		return "/";
	}
}
