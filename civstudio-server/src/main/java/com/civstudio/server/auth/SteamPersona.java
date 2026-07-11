package com.civstudio.server.auth;

/**
 * A Steam user's public profile bits used for display: their {@code personaName} (the handle shown
 * instead of the numeric SteamID) and an {@code avatarUrl}. Fetched from the Steam Web API on
 * sign-in — see {@link SteamPersonaLookup}.
 *
 * @param personaName the Steam handle
 * @param avatarUrl   the profile avatar URL (medium), or {@code null}
 */
public record SteamPersona(String personaName, String avatarUrl) {
}
