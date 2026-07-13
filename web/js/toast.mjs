"use strict";
// A minimal transient toast primitive (docs/privy-council.md §2b): stacked notices, auto-dismissing,
// click-to-close. Dependency-free; lazily creates its own #toastHost container (styled in index.html).
let host = null;
function ensureHost() {
  if (host) return host;
  host = document.getElementById("toastHost");
  if (!host) { host = document.createElement("div"); host.id = "toastHost"; document.body.appendChild(host); }
  return host;
}

/**
 * Show a transient toast. `html` is trusted markup the caller has already escaped.
 * @param {string} html the toast body
 * @param {number} ms   auto-dismiss delay (default 6500)
 * @returns {() => void} a function that dismisses it early
 */
export function toast(html, ms = 6500) {
  const el = document.createElement("div");
  el.className = "toast";
  el.innerHTML = html;
  ensureHost().appendChild(el);
  requestAnimationFrame(() => el.classList.add("in"));
  const close = () => { el.classList.remove("in"); setTimeout(() => el.remove(), 240); };
  const timer = setTimeout(close, ms);
  el.addEventListener("click", () => { clearTimeout(timer); close(); });
  return close;
}
