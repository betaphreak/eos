package com.civstudio.server.auth;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

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
}
