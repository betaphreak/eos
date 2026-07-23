package com.civstudio.server.lore;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.civstudio.server.CivStudioProperties;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Opt-in wiring of the lore chatbot (P5). Active only when {@code civstudio.lore.datasource-url} is set —
 * the pgvector DB that holds {@code wiki_chunk} (the {@code civ-pgv} Docker container locally,
 * {@code civstudio-postgres} on prod). This is a DEDICATED datasource, separate from the opt-in
 * command-log datasource ({@code spring.datasource.url}, {@link com.civstudio.server.PersistenceConfig}) —
 * the lore vector store and the session store are independent — so its {@link JdbcTemplate} is built by
 * hand here (not Boot autoconfig) to avoid any ambiguity when both datasources exist. With no lore
 * datasource these beans are absent and {@link LoreController} never registers, so the server is
 * unaffected.
 */
@Configuration
// blank-safe gate: the yml default `${CIVSTUDIO_LORE_DATASOURCE_URL:}` makes the property PRESENT as
// an empty string on machines without the lore env vars, which @ConditionalOnProperty(name=...) counts
// as "set" — Hikari then dies on the URL-less pool and every context-loading test with it. hasText
// treats blank as absent, so a clean environment simply runs without the lore beans (the intent).
@ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${civstudio.lore.datasource-url:}')")
public class LoreConfig {

	@Bean
	DataSource loreDataSource(CivStudioProperties props) {
		CivStudioProperties.Lore lore = props.getLore();
		HikariDataSource ds = new HikariDataSource();
		ds.setJdbcUrl(lore.getDatasourceUrl());
		ds.setUsername(lore.getDatasourceUsername());
		ds.setPassword(lore.getDatasourcePassword());
		ds.setMaximumPoolSize(4);
		ds.setPoolName("civstudio-lore");
		return ds;
	}

	@Bean
	LoreService loreService(DataSource loreDataSource, CivStudioProperties props) {
		return new LoreService(new JdbcTemplate(loreDataSource), props.getLore());
	}
}
