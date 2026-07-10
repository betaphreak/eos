package com.civstudio.server.auth;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * A process-lifetime {@link UserStore} — the default when no datasource is configured (mirrors
 * {@link com.civstudio.server.command.NoOpCommandStore} for the command log). Accounts are keyed
 * by {@code provider:subject} for the login lookup and by surrogate id for the principal lookup.
 */
public final class InMemoryUserStore implements UserStore {

	private final ConcurrentMap<String, AppUser> byProviderSubject = new ConcurrentHashMap<>();
	private final ConcurrentMap<String, AppUser> byId = new ConcurrentHashMap<>();

	@Override
	public AppUser upsertByProvider(String provider, String subject, String displayName,
			String avatarUrl) {
		String key = provider + ":" + subject;
		// compute atomically so concurrent logins of the same identity share one surrogate id
		AppUser user = byProviderSubject.compute(key, (k, existing) -> {
			String id = existing != null ? existing.id() : UUID.randomUUID().toString();
			return new AppUser(id, provider, subject, displayName, avatarUrl);
		});
		byId.put(user.id(), user);
		return user;
	}

	@Override
	public Optional<AppUser> findById(String id) {
		return Optional.ofNullable(byId.get(id));
	}
}
