package com.civstudio.server.web;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import com.civstudio.server.HostedSession;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Who may write to a session — the whole policy, in one place.
 * <p>
 * Every method answers the same way: {@code null} means <b>allowed</b>, anything else is the error
 * response to return. That shape lets a controller read as {@code deny... ; if (denied != null)
 * return denied;} without knowing the rules.
 * <p>
 * <b>Authenticate, then authorize.</b> Every check here does the two in that order, because they
 * answer different questions: an anonymous caller gets {@code 401} ("sign in"), a signed-in caller
 * who may not act gets {@code 403} ("you may not"). This class exists largely to make that order
 * structural: it used to be repeated per endpoint, and a Timeline clock check written in the wrong
 * order answered {@code 403} to anonymous callers that every other endpoint answered {@code 401}.
 * <p>
 * The rules, as the design notes state them:
 * <ul>
 * <li><b>Spectating is never gated</b> — reads do not come here at all.</li>
 * <li><b>A run's owner</b> gates its clock. An unowned run (the demo) is open to any signed-in
 * user; an owned one (a save slot) only to its owner. Admins bypass.</li>
 * <li><b>A Timeline's clock is admins-only</b> — it runs for everyone in it, so it belongs to no
 * player (see {@code docs/spectator-lobby.md}).</li>
 * <li><b>A colony's owner</b> gates commands against it — the Phase-2 seam: in a shared world the
 * question is about the colony, not the run.</li>
 * </ul>
 */
@Component
public class SessionAuthz {

	private final CurrentUserResolver currentUser;

	public SessionAuthz(CurrentUserResolver currentUser) {
		this.currentUser = currentUser;
	}

	/**
	 * May this request drive the session's clock (pause/resume/step/rate/stop/start)?
	 *
	 * @param hs   the session
	 * @param http the request
	 * @return {@code null} if allowed, else the error response
	 */
	public ResponseEntity<Object> denyClock(HostedSession hs, HttpServletRequest http) {
		String user = currentUser.userId(http);
		if (user == null)
			return unauthenticated("sign in to control the session");
		// a Timeline's clock is everyone's, so it is no player's — even one seated in it
		if (hs.isTimeline() && !currentUser.isAdmin(http))
			return forbidden("the Timeline's clock runs for everyone — admins only");
		if (!ownsRun(hs, user, http))
			return forbidden("not the owner of session " + hs.id());
		return null;
	}

	/**
	 * May this request command {@code colonyName} in this session? A {@code null} colony names no
	 * target — it acts on the whole run, so the run's owner is the gate (the single-colony case,
	 * unchanged from before per-colony ownership).
	 *
	 * @param hs         the session
	 * @param colonyName the colony the command names, or {@code null} for all of them
	 * @param http       the request
	 * @return {@code null} if allowed, else the error response
	 */
	public ResponseEntity<Object> denyColonyCommand(HostedSession hs, String colonyName,
			HttpServletRequest http) {
		String user = currentUser.userId(http);
		if (user == null)
			return unauthenticated("sign in to command a colony");
		if (colonyName == null) {
			if (!ownsRun(hs, user, http))
				return forbidden("not the owner of session " + hs.id());
			return null;
		}
		if (hs.colonyByName(colonyName) == null)
			return ResponseEntity.status(404)
					.body(Map.of("error", "no colony " + colonyName + " in session " + hs.id()));
		String colonyOwner = hs.ownerOf(colonyName);
		if (colonyOwner != null && !colonyOwner.equals(user) && !currentUser.isAdmin(http))
			return forbidden("not the owner of colony " + colonyName);
		return null;
	}

	/**
	 * May this request take a seat in this session? A seat belongs to a player, so it needs a
	 * signed-in one; whether the session is joinable at all is the host's call, not the policy's.
	 *
	 * @param http the request
	 * @return {@code null} if allowed, else the error response
	 */
	public ResponseEntity<Object> denyJoin(HttpServletRequest http) {
		return currentUser.userId(http) == null ? unauthenticated("sign in to join the Timeline") : null;
	}

	/**
	 * May this request post chat? Chat is open to <b>any</b> signed-in user — it is a spectator
	 * lobby, not a control surface.
	 *
	 * @param http the request
	 * @return {@code null} if allowed, else the error response
	 */
	public ResponseEntity<Object> denyChat(HttpServletRequest http) {
		return currentUser.displayName(http) == null ? unauthenticated("sign in to chat") : null;
	}

	// whether `user` may act on the RUN: an unowned run is open to any signed-in user (the demo),
	// an owned one to its owner, and an admin bypasses either way
	private boolean ownsRun(HostedSession hs, String user, HttpServletRequest http) {
		String owner = hs.owner();
		return owner == null || owner.equals(user) || currentUser.isAdmin(http);
	}

	private static ResponseEntity<Object> unauthenticated(String why) {
		return ResponseEntity.status(401).body(Map.of("error", why));
	}

	private static ResponseEntity<Object> forbidden(String why) {
		return ResponseEntity.status(403).body(Map.of("error", why));
	}
}
