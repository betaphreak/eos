package com.civstudio.server.mcp;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.civstudio.server.auth.Admins;
import com.civstudio.server.web.CurrentUserResolver;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The production {@link McpAuthz}: the MCP Streamable-HTTP endpoint is a servlet request behind the
 * same Spring Security filter chain as the REST API, so the caller's identity is on the request
 * thread. When the servlet request is reachable ({@link RequestContextHolder}) it defers to {@link
 * CurrentUserResolver} — the exact model the REST controllers use, including the dev-header path —
 * and otherwise falls back to the {@link SecurityContextHolder} on the thread.
 */
@Component
public class RequestMcpAuthz implements McpAuthz {

	private final CurrentUserResolver users;

	public RequestMcpAuthz(CurrentUserResolver users) {
		this.users = users;
	}

	@Override
	public boolean isAdmin() {
		HttpServletRequest req = request();
		return req != null ? users.isAdmin(req) : hasRole(Admins.ROLE_ADMIN);
	}

	@Override
	public String userId() {
		HttpServletRequest req = request();
		if (req != null)
			return users.userId(req);
		Authentication a = SecurityContextHolder.getContext().getAuthentication();
		return authenticated(a) ? a.getName() : null;
	}

	private static HttpServletRequest request() {
		RequestAttributes ra = RequestContextHolder.getRequestAttributes();
		return ra instanceof ServletRequestAttributes s ? s.getRequest() : null;
	}

	private static boolean hasRole(String role) {
		Authentication a = SecurityContextHolder.getContext().getAuthentication();
		return authenticated(a)
				&& a.getAuthorities().stream().anyMatch(g -> role.equals(g.getAuthority()));
	}

	private static boolean authenticated(Authentication a) {
		return a != null && a.isAuthenticated() && !(a instanceof AnonymousAuthenticationToken);
	}
}
