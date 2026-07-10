package com.civstudio.server.web;

import org.springframework.stereotype.Component;

import com.civstudio.server.CivStudioProperties;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the authenticated caller's {@code app_user} id for a request — the seam the
 * ownership checks in {@link SessionController} consult. This is the <b>Phase 1</b> stand-in
 * for real authentication (see {@code docs/authentication.md}): there is no login provider yet,
 * so a request is anonymous ({@code null}) unless the development-only {@code X-CivStudio-User}
 * header is supplied <em>and</em> trusted via {@code civstudio.auth.trust-dev-user-header}
 * (default off, since a spoofable header must never be trusted in production).
 * <p>
 * Phase 2 (Steam / OIDC login) replaces the body of {@link #userId(HttpServletRequest)} with a
 * read of the Spring Security {@code SecurityContext}; the callers and the whole ownership model
 * stay unchanged.
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
		if (trustDevHeader) {
			String header = request.getHeader(DEV_USER_HEADER);
			if (header != null && !header.isBlank())
				return header.trim();
		}
		return null; // Phase 1: no real auth. Phase 2 reads the SecurityContext here.
	}
}
