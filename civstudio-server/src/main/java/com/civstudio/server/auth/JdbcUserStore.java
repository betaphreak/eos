package com.civstudio.server.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * A durable {@link UserStore} backed by an {@code app_user} table (see
 * {@code docs/authentication.md}). Portable DDL created on first use ({@code CREATE TABLE IF NOT
 * EXISTS}, H2 + PostgreSQL), matching {@link com.civstudio.server.command.JdbcCommandStore}. The
 * surrogate id is the primary key; {@code (provider, subject)} is unique — the login lookup key.
 */
public final class JdbcUserStore implements UserStore {

	private static final RowMapper<AppUser> MAPPER = (rs, n) -> new AppUser(rs.getString("id"),
			rs.getString("provider"), rs.getString("subject"), rs.getString("display_name"),
			rs.getString("avatar_url"));

	private final JdbcTemplate jdbc;

	public JdbcUserStore(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** Create the table + unique index if absent. Portable DDL (H2 + PostgreSQL). */
	public void initSchema() {
		jdbc.execute("""
				CREATE TABLE IF NOT EXISTS app_user (
				  id           VARCHAR(64)  PRIMARY KEY,
				  provider     VARCHAR(32)  NOT NULL,
				  subject      VARCHAR(128) NOT NULL,
				  display_name VARCHAR(128),
				  avatar_url   VARCHAR(512),
				  created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
				  last_login_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
				)""");
		jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_app_user_identity "
				+ "ON app_user(provider, subject)");
	}

	@Override
	public AppUser upsertByProvider(String provider, String subject, String displayName,
			String avatarUrl) {
		// refresh the mutable fields for a returning user...
		int updated = jdbc.update("UPDATE app_user SET display_name = ?, avatar_url = ?, "
				+ "last_login_at = CURRENT_TIMESTAMP WHERE provider = ? AND subject = ?",
				displayName, avatarUrl, provider, subject);
		if (updated == 0) {
			// ...or insert a new account. A losing race on the unique (provider, subject) index
			// throws; fall through to the re-read below, which returns the winner's row.
			try {
				jdbc.update("INSERT INTO app_user(id, provider, subject, display_name, avatar_url) "
						+ "VALUES (?, ?, ?, ?, ?)", UUID.randomUUID().toString(), provider, subject,
						displayName, avatarUrl);
			} catch (DataIntegrityViolationException raced) {
				// concurrent insert won; the row now exists — read it below
			}
		}
		return findByProvider(provider, subject).orElseThrow(
				() -> new IllegalStateException("app_user vanished after upsert: " + provider + ":" + subject));
	}

	@Override
	public Optional<AppUser> findById(String id) {
		return one("SELECT * FROM app_user WHERE id = ?", id);
	}

	private Optional<AppUser> findByProvider(String provider, String subject) {
		return one("SELECT * FROM app_user WHERE provider = ? AND subject = ?", provider, subject);
	}

	private Optional<AppUser> one(String sql, Object... args) {
		List<AppUser> rows = jdbc.query(sql, MAPPER, args);
		return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
	}
}
