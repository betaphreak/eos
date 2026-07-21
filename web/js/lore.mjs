// Wiki lore panel section (P4). Fetches an entity's Anbennar wiki lore from Strapi's public REST API and
// renders it (title, infobox image, cleaned-markdown body, wiki link) into a container. Lore is public
// CC BY-SA content, so the web reads Strapi directly — no server round-trip. See docs/wiki-lore-import-plan.md.

import { renderMarkdown } from "./md.mjs";

// Strapi base: ?strapi=<url> override (for local dev, e.g. http://localhost:1337), else prod civstudio.com.
const STRAPI_BASE = (() => {
  try {
    const q = new URLSearchParams(location.search).get("strapi");
    if (q) return q.replace(/\/+$/, "");
  } catch { /* no location (tests) */ }
  return "https://civstudio.com";
})();

const cache = new Map(); // "ref/key" → lore object | null (miss cached too, so we don't re-fetch absent lore)

const esc = (s) => String(s == null ? "" : s)
  .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");

/** Fetch the one wiki-article correlated to (entityRef, entityKey), or null. */
export async function fetchLore(entityRef, entityKey) {
  const ck = entityRef + "/" + entityKey;
  if (cache.has(ck)) return cache.get(ck);
  const q = "?filters[entityRef][$eq]=" + encodeURIComponent(entityRef)
    + "&filters[entityKey][$eq]=" + encodeURIComponent(entityKey)
    + "&fields[0]=title&fields[1]=summary&fields[2]=body&fields[3]=url"
    + "&fields[4]=imageUrl&fields[5]=imageFile&locale=en&pagination[limit]=1";
  let lore = null;
  try {
    const res = await fetch(STRAPI_BASE + "/api/wiki-articles" + q, { credentials: "omit" });
    if (res.ok) {
      const j = await res.json();
      const row = j.data && j.data[0];
      if (row) lore = row.attributes || row; // Strapi 5 flattens attributes; tolerate either
    }
  } catch { /* offline / CORS — treat as no lore */ }
  cache.set(ck, lore);
  return lore;
}

/**
 * Fill `container` with the lore for (entityRef, entityKey). Shows a loading line, then the lore, or
 * clears the container when there is none (so a lore-less entity leaves no empty box).
 */
export async function fillLore(container, entityRef, entityKey) {
  if (!container) return;
  const token = (container._loreToken = (container._loreToken || 0) + 1); // guard against out-of-order fills
  container.innerHTML = '<div class="lore-loading">Loading lore…</div>';
  const lore = await fetchLore(entityRef, entityKey);
  if (container._loreToken !== token) return; // a newer selection superseded this fill
  if (!lore) { container.innerHTML = ""; return; }

  const img = lore.imageUrl
    ? `<img class="lore-img" src="${esc(lore.imageUrl)}" alt="${esc(lore.imageFile || lore.title || "")}" loading="lazy">`
    : "";
  const text = lore.body || lore.summary || "";
  const link = lore.url
    ? `<a class="lore-link" href="${esc(lore.url)}" target="_blank" rel="noopener">Read on the Anbennar Wiki ↗</a>`
    : "";
  container.innerHTML = `<div class="lore">`
    + `<div class="lore-title">${esc(lore.title || "")}</div>`
    + img
    + (text ? `<div class="lore-body">${renderMarkdown(text)}</div>` : "")
    + link
    + `<div class="lore-credit">Lore · Anbennar Wiki (CC BY-SA)</div>`
    + `</div>`;
}
