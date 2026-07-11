package com.civstudio.server.auth;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * An {@link OidcUser} whose {@link #getName() name} is CivStudio's own surrogate {@code app_user}
 * id rather than the provider's subject — so an OIDC login resolves to the <em>same</em> principal
 * identity as every other provider, and {@code CurrentUserResolver}/ownership need no special
 * casing (they always read {@code authentication.getName()}). Every other facet delegates to the
 * real OIDC user.
 */
public final class CivStudioOidcUser implements OidcUser {

	private final OidcUser delegate;
	private final String userId;
	private final boolean admin;

	public CivStudioOidcUser(OidcUser delegate, String userId, boolean admin) {
		this.delegate = delegate;
		this.userId = userId;
		this.admin = admin;
	}

	@Override
	public String getName() {
		return userId; // the app_user id — the ownership key
	}

	@Override
	public Map<String, Object> getClaims() {
		return delegate.getClaims();
	}

	@Override
	public OidcUserInfo getUserInfo() {
		return delegate.getUserInfo();
	}

	@Override
	public OidcIdToken getIdToken() {
		return delegate.getIdToken();
	}

	@Override
	public Map<String, Object> getAttributes() {
		return delegate.getAttributes();
	}

	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		if (!admin)
			return delegate.getAuthorities();
		Collection<GrantedAuthority> authorities = new ArrayList<>(delegate.getAuthorities());
		authorities.add(new SimpleGrantedAuthority(Admins.ROLE_ADMIN));
		return authorities;
	}
}
