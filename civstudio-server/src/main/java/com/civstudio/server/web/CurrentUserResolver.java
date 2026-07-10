package com.civstudio.server.web;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.civstudio.server.CivStudioProperties;

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

	public CurrentUserResolver(CivStudioProperties props) {
		this.trustDevHeader = props.getAuth().isTrustDevUserHeader();
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
}
