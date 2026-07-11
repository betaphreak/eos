package com.civstudio.server;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.civstudio.server.chat.ChatStore;
import com.civstudio.server.chat.InMemoryChatStore;
import com.civstudio.server.chat.JdbcChatStore;
import com.civstudio.server.command.CommandCodec;
import com.civstudio.server.command.CommandStore;
import com.civstudio.server.command.JdbcCommandStore;
import com.civstudio.server.command.NoOpCommandStore;
import com.zaxxer.hikari.HikariDataSource;

import tools.jackson.databind.ObjectMapper;

/**
 * Opt-in persistence of the session command log. Boot's {@code DataSourceAutoConfiguration} is
 * excluded (see {@link ServerMain}) so no datasource is ever built implicitly; instead, when
 * {@code spring.datasource.url} is set (production: the subscription's Postgres via env; tests:
 * H2), a pooled {@link DataSource} is created here and the {@link CommandStore} is the durable
 * {@link JdbcCommandStore}. With no datasource the store is a {@link NoOpCommandStore} and
 * sessions stay in-memory — the default, so local runs and the existing tests are unaffected.
 */
@Configuration
public class PersistenceConfig {

	/** A pooled datasource, only when one is configured. */
	@Bean
	@ConditionalOnProperty(name = "spring.datasource.url")
	DataSource dataSource(@Value("${spring.datasource.url}") String url,
			@Value("${spring.datasource.username:}") String username,
			@Value("${spring.datasource.password:}") String password) {
		HikariDataSource ds = new HikariDataSource();
		ds.setJdbcUrl(url);
		ds.setUsername(username);
		ds.setPassword(password);
		ds.setMaximumPoolSize(4);
		ds.setPoolName("civstudio-cmdlog");
		return ds;
	}

	/**
	 * The command store: durable (JDBC) when a {@link JdbcTemplate} is available — i.e. a
	 * datasource was configured above and Boot auto-built the template — else a no-op. Using an
	 * {@link ObjectProvider} keeps this order-safe and avoids a fragile {@code
	 * ConditionalOnMissingBean}.
	 */
	@Bean
	CommandStore commandStore(ObjectProvider<JdbcTemplate> jdbc, ObjectMapper json) {
		JdbcTemplate template = jdbc.getIfAvailable();
		if (template == null)
			return new NoOpCommandStore();
		JdbcCommandStore store = new JdbcCommandStore(template, new CommandCodec(json));
		store.initSchema();
		return store;
	}

	/**
	 * The chat store: durable ({@link JdbcChatStore}) when a datasource is configured, else an
	 * {@link InMemoryChatStore} (chat still works within a run, just isn't persisted). Unlike the
	 * command store there is no no-op fallback — the lobby always needs at least an in-memory
	 * backlog to replay to late joiners.
	 */
	@Bean
	ChatStore chatStore(ObjectProvider<JdbcTemplate> jdbc) {
		JdbcTemplate template = jdbc.getIfAvailable();
		if (template == null)
			return new InMemoryChatStore();
		JdbcChatStore store = new JdbcChatStore(template);
		store.initSchema();
		return store;
	}
}
