package com.civstudio.server.auth;

import java.util.HashSet;

import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;

import com.civstudio.server.CivStudioProperties;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * On a successful OIDC login, return the browser to the target that {@link
 * OAuth2RedirectCaptureFilter} stashed in the session (validated), defaulting to the site root.
 * This is the OIDC analogue of the redirect {@link SteamAuthController} performs inline.
 */
public final class OidcAuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

	private final CivStudioProperties props;

	public OidcAuthenticationSuccessHandler(CivStudioProperties props) {
		this.props = props;
	}

	@Override
	protected String determineTargetUrl(HttpServletRequest request, HttpServletResponse response) {
		HttpSession session = request.getSession(false);
		Object target = session == null ? null : session.getAttribute(AuthRedirects.SESSION_TARGET_ATTR);
		if (session != null)
			session.removeAttribute(AuthRedirects.SESSION_TARGET_ATTR);
		return AuthRedirects.safe(target instanceof String s ? s : null,
				new HashSet<>(props.getCors().getOrigins()));
	}
}
