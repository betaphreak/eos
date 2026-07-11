package com.civstudio.server.auth;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.authentication.NullRememberMeServices;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.security.web.authentication.rememberme.TokenBasedRememberMeServices;
import org.springframework.security.web.authentication.rememberme.TokenBasedRememberMeServices.RememberMeTokenAlgorithm;

import com.civstudio.server.CivStudioProperties;

/**
 * Wires the "remember me" persistent-login services (see {@code docs/authentication.md}). When
 * {@code civstudio.auth.remember-me.key} is set to a stable secret, a signed cookie re-authenticates
 * a user after the in-memory HTTP session is gone (a server redeploy), so they are not logged out on
 * every deploy. The user is rebuilt from the durable {@link UserStore} via {@link
 * AppUserDetailsService}; the cookie's integrity rides the signing key.
 * <p>
 * Blank key (the default, and dev/tests) → a {@link NullRememberMeServices} that issues and honours
 * nothing, so behaviour is exactly as before. The gate is in code, not {@code @ConditionalOnProperty}
 * — the kebab-case property does not bind reliably from the {@code CIVSTUDIO_AUTH_REMEMBER_ME_KEY}
 * env form (same reason {@code CivStudioProperties.Auth.Google} uses an explicit {@code enabled} flag).
 */
@Configuration
public class RememberMeConfig {

	/** The remember-me cookie name (distinct from the JSESSIONID session cookie). */
	public static final String COOKIE_NAME = "civstudio-remember";

	@Bean
	RememberMeServices rememberMeServices(CivStudioProperties props, UserStore users, Admins admins,
			@Value("${server.servlet.session.cookie.secure:false}") boolean secureCookie) {
		CivStudioProperties.Auth.RememberMe cfg = props.getAuth().getRememberMe();
		String key = cfg.getKey();
		if (key == null || key.isBlank())
			return new NullRememberMeServices();   // disabled: dev/tests behave exactly as before
		TokenBasedRememberMeServices rms = new TokenBasedRememberMeServices(
				key, new AppUserDetailsService(users, admins), RememberMeTokenAlgorithm.SHA256);
		rms.setAlwaysRemember(true);               // every login (Steam / OIDC) issues the cookie — no checkbox
		rms.setCookieName(COOKIE_NAME);
		rms.setTokenValiditySeconds((int) Duration.ofDays(cfg.getValidityDays()).toSeconds());
		rms.setUseSecureCookie(secureCookie);      // prod sets SESSION_COOKIE_SECURE=true → HTTPS-only
		// left host-only (dev.civstudio.com): the cookie is set by, and only needed on, the API host,
		// and civstudio.com subdomains are same-site so SameSite=Lax (the browser default) still sends
		// it on the site's credentialed cross-subdomain fetches.
		return rms;
	}
}
