package com.civstudio.server.auth;

import java.util.ArrayList;
import java.util.List;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * Loads a CivStudio {@link AppUser} by its surrogate id, for the remember-me flow. On a cold server
 * (post-redeploy, empty HTTP session) the remember-me cookie carries only the user id; this rebuilds
 * the authenticated principal from the durable {@link UserStore} + the {@link Admins} allow-list:
 * the {@link UserDetails#getUsername() name} is the {@code app_user} id (what {@code
 * CurrentUserResolver} reads for ownership), and {@code ROLE_ADMIN} is re-derived from the stored
 * {@code (provider, subject)} so operators keep their bypass across restarts. The password is a
 * stable empty string — these are OAuth/Steam accounts with none, and the cookie's integrity comes
 * from the server-held signing key, not a per-user secret.
 * <p>
 * Deliberately <b>not</b> registered as a Spring bean: it is used only by the remember-me services
 * (wired in {@code RememberMeConfig}), and exposing a {@code UserDetailsService} bean would switch
 * off Boot's default user auto-config and risk it being adopted as a form-login provider.
 */
public final class AppUserDetailsService implements UserDetailsService {

	private final UserStore users;
	private final Admins admins;

	public AppUserDetailsService(UserStore users, Admins admins) {
		this.users = users;
		this.admins = admins;
	}

	@Override
	public UserDetails loadUserByUsername(String id) throws UsernameNotFoundException {
		AppUser u = users.findById(id)
				.orElseThrow(() -> new UsernameNotFoundException("unknown user " + id));
		List<SimpleGrantedAuthority> authorities = new ArrayList<>();
		authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
		if (admins.isAdmin(u.provider(), u.subject(), null, u.id()))
			authorities.add(new SimpleGrantedAuthority(Admins.ROLE_ADMIN));
		// A stable, non-empty stand-in "password" (there is no real one — OAuth/Steam accounts). It
		// must be non-empty (TokenBasedRememberMeServices refuses to issue a cookie for a blank one)
		// and identical at issue- and validate-time, which (provider, subject) is per user. It only
		// feeds the token signature alongside the secret key; it is never a credential.
		String secret = u.provider() + ":" + u.subject();
		return User.withUsername(u.id()).password(secret).authorities(authorities).build();
	}
}
