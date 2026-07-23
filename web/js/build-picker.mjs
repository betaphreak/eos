"use strict";
// The BUILD PICKER — the list of buildings the crown could start, where clicking rows chooses them
// and the CLICK ORDER IS THE QUEUE ORDER (a numbered badge shows it). Civ4's "choose production",
// used by both surfaces that offer the choice: the forced pause-and-choose decree modal
// (overlays/live.mjs) and the city screen's unforced "decree a building" (city-screen.mjs).
//
// Pure DOM over the shared catalog join (build-catalog.mjs); it knows nothing about sessions or
// commands — the caller reads `picked()` and submits.
import { loadBuildCatalog, buildMeta, buildingName, buildingCost, paintBuildIcon }
  from "./build-catalog.mjs";

/**
 * Render `candidates` (bare `BUILDING_*` ids, best first) into `list`, replacing its contents.
 *
 * @param {HTMLElement} list       the container to fill
 * @param {string[]}    candidates the ids, in the order to offer them ([0] is flagged ★ advised)
 * @param {object}      opts       `onChange(picked)` fires on every pick/unpick; `advise` (default
 *                                 true) flags the first row as the court's recommendation
 * @returns {{picked:()=>string[], clear:()=>void}} the live selection, in click order
 */
export function renderPicker(list, candidates, opts = {}) {
  const { onChange = () => {}, advise = true } = opts;
  const picked = [];
  const badges = new Map();   // id -> the order-badge span

  const renumber = () => {
    for (const b of badges.values()) b.textContent = "";
    picked.forEach((id, i) => { const b = badges.get(id); if (b) b.textContent = String(i + 1); });
    onChange(picked.slice());
  };

  list.innerHTML = "";
  badges.clear();
  candidates.forEach((id, i) => list.appendChild(row(id, advise && i === 0, picked, badges, renumber)));
  renumber();

  // the catalog usually beats us here (both surfaces open on a server round-trip); if not, re-stamp
  // names/costs/icons once it lands — safe while nothing has been picked yet
  if (!buildMeta(candidates[0])) loadBuildCatalog().then(m => {
    if (m && list.isConnected && !picked.length) renderPicker(list, candidates, opts);
  });

  return { picked: () => picked.slice(), clear: () => { picked.length = 0; renumber(); } };
}

function row(id, advised, picked, badges, renumber) {
  const el = document.createElement("button");
  el.type = "button";
  el.className = "bc-item";

  const ico = document.createElement("span");
  ico.className = "bc-ico";
  paintBuildIcon(ico, id, 28);

  const name = document.createElement("span");
  name.className = "bc-name";
  name.textContent = buildingName(id);
  if (advised) {
    const rec = document.createElement("span");
    rec.className = "bc-rec";
    rec.textContent = "★ advised";
    rec.title = "The court's recommendation";
    name.appendChild(rec);
  }

  const cost = document.createElement("span");
  cost.className = "bc-cost";
  const c = buildingCost(id);
  if (c) cost.innerHTML = `${c}<span class="h">⚒</span>`;

  const ord = document.createElement("span");
  ord.className = "bc-ord";

  el.append(ico, name, cost, ord);
  el.onclick = () => {
    const i = picked.indexOf(id);
    if (i >= 0) { picked.splice(i, 1); el.classList.remove("picked"); }
    else { picked.push(id); el.classList.add("picked"); }
    renumber();
  };
  badges.set(id, ord);
  return el;
}
