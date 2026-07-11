package com.civstudio.server.auth;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * Wires the auth collaborators (see {@code docs/authentication.md}). The {@link UserStore} follows
 * the same opt-in-persistence pattern as the command log: durable ({@link JdbcUserStore}) when a
 * datasource is configured — a {@link JdbcTemplate} is then available — else an
 * {@link InMemoryUserStore}. The {@link SteamOpenId} bean is the real HTTP client in production;
 * tests provide a stub to avoid the network.
 */
@Configuration
public class AuthConfig {

	@Bean
	UserStore userStore(ObjectProvider<JdbcTemplate> jdbc) {
		JdbcTemplate template = jdbc.getIfAvailable();
		if (template == null)
			return new InMemoryUserStore();
		JdbcUserStore store = new JdbcUserStore(template);
		store.initSchema();
		return store;
	}

	@Bean
	SteamOpenId steamOpenId() {
		return new HttpSteamOpenId();
	}

	/**
	 * The OIDC user service used by {@code oauth2Login} (phase 3): load the provider's OIDC user,
	 * upsert it into our {@link UserStore} (provider = the registration id, e.g. {@code google}),
	 * and wrap it so the authenticated principal's name is our surrogate {@code app_user} id — the
	 * same identity every other provider resolves to. Defined unconditionally (harmless when no
	 * OIDC provider is configured; it is simply never invoked).
	 */
	@Bean
	OAuth2UserService<OidcUserRequest, OidcUser> oidcUserService(UserStore users) {
		OidcUserService delegate = new OidcUserService();
		return request -> {
			OidcUser oidc = delegate.loadUser(request);
			String provider = request.getClientRegistration().getRegistrationId();
			String subject = oidc.getSubject();
			String displayName = oidc.getFullName() != null ? oidc.getFullName()
					: oidc.getEmail() != null ? oidc.getEmail() : subject;
			String avatar = asString(oidc.getAttributes().get("picture"));
			AppUser user = users.upsertByProvider(provider, subject, displayName, avatar);
			return new CivStudioOidcUser(oidc, user.id());
		};
	}

	private static String asString(Object value) {
		return value instanceof String s ? s : null;
	}
}
