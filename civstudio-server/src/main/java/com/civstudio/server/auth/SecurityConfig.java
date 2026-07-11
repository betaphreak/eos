package com.civstudio.server.auth;

import static org.springframework.security.config.Customizer.withDefaults;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

import com.civstudio.server.CivStudioProperties;

/**
 * Spring Security for the spectator/interactive server (see {@code docs/authentication.md},
 * phase 2). The chain is deliberately <b>permit-all</b>: spectating stays anonymous and
 * per-session authorization is enforced in {@code SessionController} against the owner, not by URL
 * rules. Security's job here is only to <em>establish and carry</em> the authenticated principal —
 * the login is the {@link SteamAuthController}, which saves the context into the HTTP session via
 * the {@link #securityContextRepository() repository} below, and the cookie (JSESSIONID) carries
 * it on later requests.
 * <p>
 * CSRF token protection is disabled: this is a JSON API with no server-rendered forms, and the
 * session cookie is {@code SameSite=Lax} (see {@code application.yml}) scoped to the site's parent
 * domain in production, so a cross-site POST from another origin does not carry it. CORS is locked
 * to the site origins ({@link CorsConfigurationSource}), and credentials are required for the
 * cookie to flow cross-subdomain.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

	@Bean
	SecurityFilterChain filterChain(HttpSecurity http,
			SecurityContextRepository securityContextRepository,
			ObjectProvider<ClientRegistrationRepository> clientRegistrations,
			OAuth2UserService<OidcUserRequest, OidcUser> oidcUserService,
			RememberMeServices rememberMeServices,
			CivStudioProperties props) throws Exception {
		http
				// resolves the bean named "corsConfigurationSource" (WebConfig) by name, avoiding the
				// by-type clash with MVC's mvcHandlerMappingIntrospector
				.cors(withDefaults())
				.csrf(csrf -> csrf.disable())
				// authorization is per-session (owner check in the controller), not URL-based;
				// everything is reachable and anonymous spectating keeps working
				.authorizeHttpRequests(a -> a.anyRequest().permitAll())
				// a session (and its cookie) is created only when login saves a context — anonymous
				// spectators stay stateless
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
				.securityContext(sc -> sc.securityContextRepository(securityContextRepository))
				.anonymous(withDefaults())
				// no form login / basic (the Steam controller is the login); our own logout endpoint
				.formLogin(f -> f.disable())
				.httpBasic(b -> b.disable())
				.logout(l -> l.disable());

		// OIDC (Google) sign-in — wired only when a provider is configured (GoogleOAuthConfig
		// creates the repository conditionally), so a Steam-only server adds nothing. The user
		// service upserts the app_user + rebinds the principal to our id; the capture filter +
		// success handler carry the browser back to the site it started from.
		if (clientRegistrations.getIfAvailable() != null) {
			http
					.addFilterBefore(new OAuth2RedirectCaptureFilter(props),
							OAuth2AuthorizationRequestRedirectFilter.class)
					.oauth2Login(o -> o
							.userInfoEndpoint(u -> u.oidcUserService(oidcUserService))
							.successHandler(new OidcAuthenticationSuccessHandler(props)));
		}

		// Remember-me: when a stable signing key is configured, add the filter that re-authenticates a
		// returning user from the signed cookie (so a redeploy that drops the in-memory session no
		// longer logs everyone out). The shared RememberMeServices is picked up by the oauth2Login
		// filter to issue the cookie on OIDC login; the Steam controller issues it inline. Absent a key
		// the bean is a no-op NullRememberMeServices and this whole block is skipped (dev/tests).
		String rmKey = props.getAuth().getRememberMe().getKey();
		if (rmKey != null && !rmKey.isBlank())
			http.rememberMe(rm -> rm.rememberMeServices(rememberMeServices).key(rmKey));
		return http.build();
	}

	/** Where the authenticated {@code SecurityContext} is stored/loaded — the HTTP session. */
	@Bean
	SecurityContextRepository securityContextRepository() {
		return new HttpSessionSecurityContextRepository();
	}
}
