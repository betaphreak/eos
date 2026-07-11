package com.civstudio.server.auth;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.civstudio.server.CivStudioProperties;

/**
 * The operator allow-list (see {@code docs/authentication.md}). Membership is decided from
 * {@code civstudio.auth.admins} config, matched case-insensitively against several identifiers a
 * user might be listed by — their {@code app_user} id, provider subject (SteamID64 / Google
 * {@code sub}), {@code provider:subject}, or OIDC email — so an operator can be pinned by whatever
 * they know without looking up a surrogate id. A matching user is granted {@code ROLE_ADMIN} at
 * login, which lets them control/command any session (bypassing ownership).
 */
@Component
public final class Admins {

	/** The authority granted to allow-listed operators. */
	public static final String ROLE_ADMIN = "ROLE_ADMIN";

	private final Set<String> entries;

	public Admins(CivStudioProperties props) {
		this.entries = props.getAuth().getAdmins().stream()
				.filter(s -> s != null && !s.isBlank())
				.map(s -> s.trim().toLowerCase())
				.collect(Collectors.toUnmodifiableSet());
	}

	/**
	 * Whether a user with these identities is an operator.
	 *
	 * @param provider the identity provider (e.g. {@link AppUser#STEAM})
	 * @param subject  the provider subject (SteamID64 / OIDC sub)
	 * @param email    the OIDC email, or {@code null}
	 * @param userId   the surrogate {@code app_user} id
	 */
	public boolean isAdmin(String provider, String subject, String email, String userId) {
		return has(userId) || has(subject) || has(provider + ":" + subject) || has(email);
	}

	/** Whether this single identifier (e.g. the dev-header user value) is allow-listed. */
	public boolean isAdmin(String identifier) {
		return has(identifier);
	}

	private boolean has(String value) {
		return value != null && !entries.isEmpty() && entries.contains(value.toLowerCase());
	}
}
