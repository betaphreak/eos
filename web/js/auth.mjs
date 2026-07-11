"use strict";
// Site-wide sign-in control for the map viewer (index.html header). Auth is per-picked-server:
// every call targets LIVE_BASE — the server chosen on the loading screen — and carries the
// session cookie (credentials: include), so it only works when the server is same-site with the
// site (the civstudio.com cloud servers; localhost for local dev). See docs/authentication.md.
import { BUNDLE } from "./core.mjs";

const LIVE_BASE = new URLSearchParams(location.search).get("live")
  || (BUNDLE.live && BUNDLE.live.base) || "https://dev.civstudio.com";

// how each provider id renders in the sign-in menu; the server's /providers says which are offered
const PROVIDER_LABEL = { steam: "🎮  Steam", google: "G  Google" };

// start a provider's sign-in, returning here afterwards. Steam is our own OpenID controller;
// OIDC providers use Spring Security's /oauth2/authorization/{id} (the redirect param is captured
// server-side and honoured on success).
function startLogin(provider) {
  const back = encodeURIComponent(location.href);
  const path = provider === "steam"
    ? `/api/auth/steam/login?redirect=${back}`
    : `/oauth2/authorization/${provider}?redirect=${back}`;
  location.href = LIVE_BASE + path;
}

async function logout() {
  try { await fetch(LIVE_BASE + "/api/auth/logout", { method: "POST", credentials: "include" }); }
  catch (err) { /* reload anyway to reflect signed-out state */ }
  location.reload();
}

const esc = s => String(s).replace(/[&<>"]/g, c =>
  ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));

async function getJson(path) {
  const r = await fetch(LIVE_BASE + path, { credentials: "include" });
  if (!r.ok) throw new Error(String(r.status));
  return r.json();
}

export async function initSiteAuth() {
  const box = document.getElementById("siteAuth");
  if (!box) return;
  // fetch independently with fallbacks: a server without /providers (older build) still shows the
  // Steam button; an unreachable /me just reads as signed-out
  const providers = await getJson("/api/auth/providers")
    .then(p => Array.isArray(p && p.providers) ? p.providers : ["steam"])
    .catch(() => ["steam"]);
  const me = await getJson("/api/auth/me")
    .then(m => m || { authenticated: false })
    .catch(() => ({ authenticated: false }));
  render(box, providers, me);
}

function render(box, providers, me) {
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
