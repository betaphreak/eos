package com.civstudio.server.auth;

import java.util.Optional;

/**
 * Storage for {@link AppUser accounts}. Like the command log, persistence is opt-in: with no
 * datasource configured the server uses {@link InMemoryUserStore} (accounts live only for the
 * process lifetime — fine for local dev and tests), and with {@code spring.datasource.url} set it
 * uses the durable {@link JdbcUserStore} (see {@code AuthConfig}). Either way a login
 * {@linkplain #upsertByProvider upserts} the user and the rest of the server works the same.
 */
public interface UserStore {

	/**
	 * Find the user for this external identity, creating one (with a fresh surrogate id) if none
	 * exists, and refreshing its display name / avatar on every login.
	 *
	 * @param provider    the identity provider (e.g. {@link AppUser#STEAM})
	 * @param subject     the provider's stable subject (e.g. a SteamID64)
	 * @param displayName the current display name
	 * @param avatarUrl   the current avatar URL, or {@code null}
	 * @return the resolved (created or updated) user
	 */
	AppUser upsertByProvider(String provider, String subject, String displayName, String avatarUrl);

	/**
	 * Look up a user by its surrogate id (the ownership key carried in the authenticated
	 * principal).
	 *
	 * @param id the surrogate user id
	 * @return the user, or empty if unknown
	 */
	Optional<AppUser> findById(String id);
}
