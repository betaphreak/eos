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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.civstudio.server.CivStudioProperties;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

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

	private final SteamOpenId steam;
	private final SteamPersonaLookup personas;
	private final UserStore users;
	private final SecurityContextRepository securityContextRepository;
	private final CivStudioProperties props;
	private final Admins admins;

	public SteamAuthController(SteamOpenId steam, SteamPersonaLookup personas, UserStore users,
			SecurityContextRepository securityContextRepository, CivStudioProperties props,
			Admins admins) {
		this.steam = steam;
		this.personas = personas;
		this.users = users;
		this.securityContextRepository = securityContextRepository;
		this.props = props;
		this.admins = admins;
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
		// resolve the human-readable handle + avatar (needs a Web API key); fall back to the SteamID
		String id = steamId.get();
		Optional<SteamPersona> persona = personas.lookup(id);
		String displayName = persona.map(SteamPersona::personaName).filter(n -> !n.isBlank()).orElse(id);
		String avatar = persona.map(SteamPersona::avatarUrl).orElse(null);
		AppUser user = users.upsertByProvider(AppUser.STEAM, id, displayName, avatar);
		authenticate(user, request, response);
		log.info("steam sign-in: user {} (steamid {}, handle {})", user.id(), user.subject(),
				user.displayName());
		return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build();
	}

	// build an authenticated context for the user and persist it into the session so the cookie
	// carries it on subsequent requests (principal = the surrogate user id — the ownership key)
	private void authenticate(AppUser user, HttpServletRequest request, HttpServletResponse response) {
		List<SimpleGrantedAuthority> authorities = new java.util.ArrayList<>();
		authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
		if (admins.isAdmin(user.provider(), user.subject(), null, user.id()))
			authorities.add(new SimpleGrantedAuthority(Admins.ROLE_ADMIN));
		Authentication auth = UsernamePasswordAuthenticationToken.authenticated(user.id(), null,
				authorities);
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

	// only redirect to a trusted target (open-redirect guard) — shared with the OIDC flow
	private String safeRedirect(String target) {
		return AuthRedirects.safe(target, new HashSet<>(props.getCors().getOrigins()));
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
