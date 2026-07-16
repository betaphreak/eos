"use strict";
// Site-wide sign-in control for the map viewer (index.html header). Auth is per-picked-server:
// every call targets LIVE_BASE — the server chosen on the loading screen — and carries the
// session cookie (credentials: include), so it only works when the server is same-site with the
// site (the civstudio.com cloud servers; localhost for local dev). See docs/authentication.md.
import { SERVER_BASE as LIVE_BASE } from "./core.mjs";   // one resolver, in core — see its header

// how each provider id renders in the sign-in menu; the server's /providers says which are offered
const PROVIDER_LABEL = { steam: "🎮  Steam", google: "G  Google" };

// start a provider's sign-in in a popup, so the site stays put. Steam is our own OpenID
// controller; OIDC providers use Spring Security's /oauth2/authorization/{id} (the redirect param
// is captured server-side and honoured on success). The popup returns to this page's URL, where an
// inline script in index.html (window.name === "civstudio-login") posts back to us and closes it;
// we also poll /api/auth/me as a fallback. Either way we refresh in place — no full-page navigation.
// If the popup is blocked, fall back to the old full-page redirect.
function startLogin(provider) {
  const back = encodeURIComponent(location.href);
  const url = LIVE_BASE + (provider === "steam"
    ? `/api/auth/steam/login?redirect=${back}`
    : `/oauth2/authorization/${provider}?redirect=${back}`);
  const w = 520, h = 700;
  const x = (window.screenX || 0) + Math.max(0, (window.outerWidth - w) / 2);
  const y = (window.screenY || 0) + Math.max(0, (window.outerHeight - h) / 2);
  const popup = window.open(url, "civstudio-login", `popup,width=${w},height=${h},left=${x},top=${y}`);
  if (!popup) { location.href = url; return; }   // popup blocked → full-page redirect
  const started = Date.now();
  const timer = setInterval(async () => {
    let me = null;
    try { me = await getJson("/api/auth/me"); } catch { /* keep polling */ }
    if (me && me.authenticated) { clearInterval(timer); closePopup(popup); refresh(); return; }
    if (popup.closed || Date.now() - started > 180000) { clearInterval(timer); refresh(); } // cancelled/timeout
  }, 1200);
}

function closePopup(p) { try { p.close(); } catch { /* cross-origin/already closed */ } }

async function logout() {
  try { await fetch(LIVE_BASE + "/api/auth/logout", { method: "POST", credentials: "include" }); }
  catch (err) { /* refresh anyway to reflect signed-out state */ }
  refresh();
}

const esc = s => String(s).replace(/[&<>"]/g, c =>
  ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));

async function getJson(path) {
  const r = await fetch(LIVE_BASE + path, { credentials: "include" });
  if (!r.ok) throw new Error(String(r.status));
  return r.json();
}

export async function initSiteAuth() {
  if (!document.getElementById("siteAuth")) return;
  // a sign-in popup posts "civstudio-auth" back here on completion (see index.html) → refresh in place
  window.addEventListener("message", e => {
    if (e.origin === location.origin && e.data === "civstudio-auth") refresh();
  });
  await refresh();
}

// (re)fetch the offered providers + current identity and repaint the control. Called at init, when a
// sign-in popup completes, and after sign-out — so auth state updates without a page navigation.
async function refresh() {
  const box = document.getElementById("siteAuth");
  if (!box) return;
  // fetch independently with fallbacks: a server without /providers (older build) still shows the
  // Steam button; an unreachable /me just reads as signed-out
  const providers = await getJson("/api/auth/providers")
    .then(p => Array.isArray(p && p.providers) ? p.providers : ["steam"])
    .catch(() => ["steam"]);
  const me = await getJson("/api/auth/me")
    .then(m => m || { authenticated: false })
    .catch(() => ({ authenticated: false })); // unreachable /me → treat as signed-out (controls stay gated)
  render(box, providers, me);
}

function render(box, providers, me) {
  // the play/pause/speed transport requires a signed-in user (see docs/authentication.md); mark
  // the body so CSS can dim those controls and panel.mjs can ignore their click/keyboard paths
  document.body.classList.toggle("auth-anon", !me.authenticated);
  box.innerHTML = "";
  const btn = document.createElement("button");
  btn.className = "site-auth-btn";
  const menu = document.createElement("div");
  menu.className = "site-auth-menu";
  menu.hidden = true;

  if (me.authenticated) {
    const who = me.displayName || me.id;
    btn.innerHTML = (me.avatarUrl ? `<img src="${esc(me.avatarUrl)}" alt="">` : "🎮 ")
      + `<span>${esc(who)}</span> ▾`;
    const out = document.createElement("button");
    out.textContent = "Sign out";
    out.onclick = logout;
    menu.appendChild(out);
  } else {
    btn.innerHTML = "Sign in ▾";
    providers.forEach(p => {
      const b = document.createElement("button");
      b.textContent = PROVIDER_LABEL[p] || p;
      b.onclick = () => startLogin(p);
      menu.appendChild(b);
    });
  }

  btn.onclick = e => { e.stopPropagation(); menu.hidden = !menu.hidden; };
  document.addEventListener("click", () => { menu.hidden = true; });
  box.appendChild(btn);
  box.appendChild(menu);
}
