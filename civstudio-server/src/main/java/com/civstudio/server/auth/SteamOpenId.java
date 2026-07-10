package com.civstudio.server.auth;

import java.util.Map;
import java.util.Optional;

/**
 * The Steam <b>OpenID 2.0</b> "Sign in through Steam" flow (see {@code docs/authentication.md}).
 * Steam is an OpenID 2.0 provider — not OIDC/OAuth2, and Spring Security dropped OpenID 2.0 — so
 * this is hand-rolled and small: build the redirect to Steam, then <em>verify</em> the return by
 * asking Steam to authenticate the assertion ({@code openid.mode=check_authentication}) before
 * trusting the claimed SteamID. This interface is the seam the {@link SteamAuthController} depends
 * on; tests substitute a stub so no network call is made.
 */
public interface SteamOpenId {

	/**
	 * Build the URL to redirect the browser to for Steam sign-in.
	 *
	 * @param realm    the OpenID realm (the site origin, e.g. {@code https://dev.civstudio.com})
	 * @param returnTo the absolute callback URL Steam sends the browser back to
	 * @return the {@code https://steamcommunity.com/openid/login?…} redirect URL
	 */
	String authenticationRequestUrl(String realm, String returnTo);

	/**
	 * Verify the OpenID 2.0 return parameters with Steam and extract the authenticated SteamID64.
	 * Implementations MUST re-post the assertion to Steam with {@code check_authentication} and
	 * only return an id when Steam replies {@code is_valid:true} — never trust the redirect params
	 * alone.
	 *
	 * @param params the query parameters Steam returned on the callback (the {@code openid.*} set)
	 * @return the verified SteamID64, or empty if verification failed
	 */
	Optional<String> verify(Map<String, String> params);
}
