"use strict";
// Which server are we talking to? One resolver, importable before the world exists.
//
// core.mjs used to own this, and core reads window.BUNDLE at import time — fine for the map, fatal
// for anything that must run BEFORE the bundle lands. The lobby opens during the load and the
// sign-in inside it has to work there, so the answer to "which server" cannot depend on the world
// having already arrived. Hence this module: no imports, no BUNDLE at import time, no DOM.
//
// The order is the one core always used: an explicit ?live=<url> beats the base the bootstrap
// recorded on the fetched bundle, which beats the default cloud server.

/** The default when nothing else says otherwise — the public dev server. */
export const DEFAULT_BASE = "https://dev.civstudio.com";

/**
 * Resolve the server base, trailing slashes stripped.
 *
 * @param given an explicit base to prefer (the boot flow knows which server was picked before the
 *              bundle exists to record it); omit to resolve from the page
 */
export function resolveBase(given) {
  if (given) return strip(given);
  const explicit = new URLSearchParams(location.search).get("live");
  if (explicit) return strip(explicit);
  const bundle = window.BUNDLE;
  return strip((bundle && bundle.live && bundle.live.base) || DEFAULT_BASE);
}

const strip = url => String(url).replace(/\/+$/, "");
