package com.civstudio.server.auth;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.civstudio.server.CivStudioProperties;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * The "Sign in through Steam" endpoints (Steam OpenID 2.0 — see {@code docs/authentication.md},
 * phase 2, the default sign-in). Flow:
 * <ol>
 * <li>{@code GET /api/auth/steam/login?redirect=…} — 302 to Steam with the OpenID request.</li>
 * <li>{@code GET /api/auth/steam/return?…} — Steam's callback; {@linkplain SteamOpenId#verify
 * verified} against Steam, the {@link AppUser} upserted, an authenticated {@code SecurityContext}
 * saved into the session (so the JSESSIONID cookie carries it), then 302 back to the site.</li>
 * <li>{@code GET /api/auth/me} — the current user (or {@code authenticated:false}).</li>
 * <li>{@code POST /api/auth/logout} — drop the session.</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/auth")
public class SteamAuthController {

	private static final Logger log = LoggerFactory.getLogger(SteamAuthController.class);

	private static final List<SimpleGrantedAuthority> USER_AUTHORITIES = List
			.of(new SimpleGrantedAuthority("ROLE_USER"));

	private final SteamOpenId steam;
	private final UserStore users;
	private final SecurityContextRepository securityContextRepository;
	private final CivStudioProperties props;

	public SteamAuthController(SteamOpenId steam, UserStore users,
			SecurityContextRepository securityContextRepository, CivStudioProperties props) {
		this.steam = steam;
		this.users = users;
		this.securityContextRepository = securityContextRepository;
		this.props = props;
	}

	/** Redirect the browser to Steam's OpenID sign-in. */
	@GetMapping("/steam/login")
	public ResponseEntity<Void> login(@RequestParam(required = false) String redirect,
			HttpServletRequest request) {
		String realm = realm(request);
		String target = safeRedirect(redirect);
		// carry the post-login target through the round trip on our own return URL
		String returnTo = realm + "/api/auth/steam/return?redirect="
				+ URLEncoderUtf8.encode(target);
		String url = steam.authenticationRequestUrl(realm, returnTo);
		return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
	}

	/** Steam's callback: verify, upsert the user, establish the session, bounce back to the site. */
	@GetMapping("/steam/return")
	public ResponseEntity<Void> steamReturn(@RequestParam(required = false) String redirect,
			HttpServletRequest request, HttpServletResponse response) {
		Optional<String> steamId = steam.verify(openidParams(request));
		String target = safeRedirect(redirect);
		if (steamId.isEmpty()) {
			log.info("steam sign-in failed verification");
			return ResponseEntity.status(HttpStatus.FOUND)
					.location(URI.create(appendError(target))).build();
		}
		AppUser user = users.upsertByProvider(AppUser.STEAM, steamId.get(), steamId.get(), null);
		authenticate(user, request, response);
		log.info("steam sign-in: user {} (steamid {})", user.id(), user.subject());
		return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build();
	}

	/** The current authenticated user, or {@code {authenticated:false}}. */
	@GetMapping("/me")
	public Map<String, Object> me() {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken)
			return Map.of("authenticated", false);
		return users.findById(auth.getName())
				.map(u -> Map.<String, Object>of("authenticated", true, "id", u.id(), "provider",
						u.provider(), "displayName", u.displayName(),
						"avatarUrl", u.avatarUrl() == null ? "" : u.avatarUrl()))
				.orElse(Map.of("authenticated", false));
	}

	/** Sign out: invalidate the session and clear the security context. */
	@PostMapping("/logout")
	public ResponseEntity<Void> logout(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session != null)
			session.invalidate();
		SecurityContextHolder.clearContext();
		return ResponseEntity.noContent().build();
	}

	// build an authenticated context for the user and persist it into the session so the cookie
	// carries it on subsequent requests (principal = the surrogate user id — the ownership key)
	private void authenticate(AppUser user, HttpServletRequest request, HttpServletResponse response) {
		Authentication auth = UsernamePasswordAuthenticationToken.authenticated(user.id(), null,
				USER_AUTHORITIES);
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(auth);
		SecurityContextHolder.setContext(context);
		securityContextRepository.saveContext(context, request, response);
	}

	// the openid.* parameters Steam returned (single-valued), for check_authentication
	private static Map<String, String> openidParams(HttpServletRequest request) {
		Map<String, String> params = new java.util.LinkedHashMap<>();
		for (Map.Entry<String, String[]> e : request.getParameterMap().entrySet())
			if (e.getKey().startsWith("openid.") && e.getValue().length > 0)
				params.put(e.getKey(), e.getValue()[0]);
		return params;
	}

	// the OpenID realm = this server's origin. A configured value wins (behind a proxy the derived
	// host may be wrong); otherwise derive scheme://host from the request.
	private String realm(HttpServletRequest request) {
		String configured = props.getAuth().getSteam().getRealm();
		if (configured != null && !configured.isBlank())
			return stripTrailingSlash(configured.trim());
		String host = request.getHeader("Host");
		return request.getScheme() + "://" + host;
	}

	// only redirect to a relative same-site path or to a trusted origin (a configured site origin,
	// or localhost for dev) — never an arbitrary URL (open-redirect guard). Defaults to the root.
	private String safeRedirect(String target) {
		if (target == null || target.isBlank())
			return "/";
		if (target.startsWith("/") && !target.startsWith("//"))
			return target; // a same-site relative path
		try {
			URI uri = URI.create(target);
			String host = uri.getHost();
			String origin = uri.getScheme() + "://" + uri.getAuthority();
			if (allowedOrigins().contains(origin) || "localhost".equals(host) || "127.0.0.1".equals(host))
				return target;
		} catch (RuntimeException malformed) {
			// fall through to the safe default
		}
		return "/";
	}

	private Set<String> allowedOrigins() {
		return new HashSet<>(props.getCors().getOrigins());
	}

	private static String appendError(String target) {
		return target + (target.contains("?") ? "&" : "?") + "login=failed";
	}

	private static String stripTrailingSlash(String s) {
		return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
	}

	// tiny helper to keep the URL-encode noise out of login()
	private static final class URLEncoderUtf8 {
		static String encode(String s) {
			return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
		}
	}
}
