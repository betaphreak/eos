package com.civstudio.server.auth;

import java.io.IOException;
import java.util.HashSet;

import org.springframework.web.filter.OncePerRequestFilter;

import com.civstudio.server.CivStudioProperties;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Captures the {@code redirect} query param on an OIDC sign-in start
 * ({@code /oauth2/authorization/**}) into the session, validated by {@link AuthRedirects}, so the
 * {@link OidcAuthenticationSuccessHandler} can return the browser to where it started once Google
 * finishes. Mirrors what {@link SteamAuthController} does inline for the Steam flow. Runs before
 * Spring's authorization-request redirect filter; a no-op on every other request.
 */
public final class OAuth2RedirectCaptureFilter extends OncePerRequestFilter {

	private final CivStudioProperties props;

	public OAuth2RedirectCaptureFilter(CivStudioProperties props) {
		this.props = props;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain chain) throws ServletException, IOException {
		if (request.getRequestURI().startsWith("/oauth2/authorization/")) {
			String redirect = request.getParameter("redirect");
			if (redirect != null && !redirect.isBlank()) {
				String safe = AuthRedirects.safe(redirect, new HashSet<>(props.getCors().getOrigins()));
				request.getSession(true).setAttribute(AuthRedirects.SESSION_TARGET_ATTR, safe);
			}
		}
		chain.doFilter(request, response);
	}
}
