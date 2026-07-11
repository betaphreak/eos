package com.civstudio.server.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;

import com.civstudio.server.CivStudioProperties;

/**
 * Registers Google as an OIDC sign-in provider — but only when it is configured (see
 * {@code docs/authentication.md} phase 3). The {@link ClientRegistrationRepository} bean is
 * created solely when {@code civstudio.auth.google.client-id} is set, so an unconfigured server
 * has no repository at all: {@code SecurityConfig} then skips {@code oauth2Login} entirely and the
 * server runs Steam-only. Google's endpoints/scopes come from Spring's
 * {@link CommonOAuth2Provider#GOOGLE} defaults; only the client id/secret (and optionally the
 * redirect URI) are ours.
 */
@Configuration
@ConditionalOnProperty(name = "civstudio.auth.google.client-id")
public class GoogleOAuthConfig {

	@Bean
	ClientRegistrationRepository clientRegistrationRepository(CivStudioProperties props) {
		CivStudioProperties.Auth.Google g = props.getAuth().getGoogle();
		ClientRegistration google = CommonOAuth2Provider.GOOGLE.getBuilder("google")
				.clientId(g.getClientId())
				.clientSecret(g.getClientSecret())
				.redirectUri(g.getRedirectUri())
				.build();
		return new InMemoryClientRegistrationRepository(google);
	}
}
