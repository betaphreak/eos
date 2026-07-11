package com.civstudio.server.web;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.civstudio.server.CivStudioProperties;
import com.civstudio.server.auth.Admins;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the authenticated caller's {@code app_user} id for a request — the seam the ownership
 * checks in {@link SessionController} consult (see {@code docs/authentication.md}). The real
 * source is the Spring Security {@code SecurityContext} established by login (phase 2): its
 * principal name is the surrogate user id. When there is no logged-in principal the request is
 * anonymous ({@code null}), except that a development-only {@code X-CivStudio-User} header is
 * honored as a fallback when — and only when — {@code civstudio.auth.trust-dev-user-header} is set
 * (default off, since a spoofable header must never be trusted in production).
 */
@Component
public class CurrentUserResolver {

	/** The development/test header carrying the caller's user id (honored only when trusted). */
	static final String DEV_USER_HEADER = "X-CivStudio-User";

	private final boolean trustDevHeader;
	private final Admins admins;

	public CurrentUserResolver(CivStudioProperties props, Admins admins) {
		this.trustDevHeader = props.getAuth().isTrustDevUserHeader();
		this.admins = admins;
	}

	/**
	 * The caller's user id, or {@code null} if the request is anonymous.
	 *
	 * @param request the current request
	 * @return the authenticated user id, or {@code null}
	 */
	public String userId(HttpServletRequest request) {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken))
			return auth.getName();
		if (trustDevHeader) {
			String header = request.getHeader(DEV_USER_HEADER);
			if (header != null && !header.isBlank())
				return header.trim();
		}
		return null;
	}

	/**
	 * Whether the caller is an operator (see {@link Admins}). The real source is the
	 * {@code ROLE_ADMIN} authority granted at login; the dev-header path (when trusted) checks the
	 * header value against the allow-list so tests can exercise admin without a login.
	 *
	 * @param request the current request
	 * @return {@code true} if the caller is an admin
	 */
	public boolean isAdmin(HttpServletRequest request) {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
			if (auth.getAuthorities().stream().anyMatch(a -> Admins.ROLE_ADMIN.equals(a.getAuthority())))
				return true;
		}
		if (trustDevHeader) {
			String header = request.getHeader(DEV_USER_HEADER);
			return header != null && admins.isAdmin(header.trim());
		}
		return false;
	}
}
