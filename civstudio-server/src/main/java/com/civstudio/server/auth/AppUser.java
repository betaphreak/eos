package com.civstudio.server.auth;

/**
 * A CivStudio account — one canonical user behind one or more external identities (see
 * {@code docs/authentication.md}). The surrogate {@link #id} is what a session's {@code owner}
 * references (never the provider key), so a future "link accounts" feature can merge identities
 * under one id without rewriting session ownership. A login resolves to a user by its
 * {@code (provider, subject)} pair.
 *
 * @param id          the surrogate CivStudio user id (a UUID string) — the ownership key
 * @param provider    the identity provider: {@code "steam"} (default) | {@code "google"} | …
 * @param subject     the provider's stable subject — a SteamID64 for Steam, an OIDC {@code sub}
 * @param displayName the human-readable name (persona name for Steam), or the subject as fallback
 * @param avatarUrl   an avatar URL, or {@code null} if unknown
 */
public record AppUser(String id, String provider, String subject, String displayName,
		String avatarUrl) {

	/** The Steam provider tag (the default sign-in). */
	public static final String STEAM = "steam";
}
