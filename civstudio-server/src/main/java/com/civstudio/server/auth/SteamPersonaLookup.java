package com.civstudio.server.auth;

import java.util.Optional;

/**
 * Looks up a Steam user's {@link SteamPersona} (handle + avatar) by SteamID64. Steam OpenID only
 * proves the SteamID; the human-readable handle comes from the Steam Web API's
 * {@code ISteamUser/GetPlayerSummaries}, which needs an API key. Returns {@link Optional#empty()}
 * when no key is configured or the lookup fails — the caller then falls back to the SteamID as the
 * display name. See {@code docs/authentication.md}.
 */
public interface SteamPersonaLookup {

	/** The user's persona, or empty if unavailable (no key / network error / unknown id). */
	Optional<SteamPersona> lookup(String steamId64);
}
