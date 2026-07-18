// The technology tree — the Technology advisor's full-canvas map-mode. It renders over the
// stage region (#techModal covers only the stage; the top bar, advisor sub-bar and right rail
// stay live — see styles.css .tech-modal), owns its DOM (the #techModal markup in index.html),
// and loads its data lazily on first open (GET /api/techs from the spectator server, gunzipped
// in-page via DecompressionStream — the plots.mjs pattern). While it is up the map's paint()
// bails (S.techOpen), so nothing renders behind it; leaving the advisor (Esc → mainmap, or
// picking another advisor) closes it and repaints the map.
//
// Layout is driven by the C2C grid: iGridX is the horizontal era-timeline column,
// iGridY the vertical lane (see docs/tech-tree.md). Cards sit on that lattice, hairline
// SVG elbows draw the prerequisites (solid = AND, dashed = OR), and hovering a tech
// lights its whole prerequisite chain in gold while the rest dims. Selecting a node opens
// its detail in the shared right rail (the province/advisor rail — panel.mjs showRail).
import { S, apiUrl } from "./core.mjs";
import { draw } from "./repaint.mjs";
import { pausePlayback } from "./clock.mjs";
import { showRail, renderRail } from "./rail.mjs";
import { liveKnownTechs, liveResearch } from "./overlays/live.mjs";

const railEl = () => document.getElementById("rail");

// the live colony's known techs (from the SSE snapshot) — the researched-state source. When
// non-empty the tree dims what the colony has NOT unlocked yet (techs and their buildings); empty
// (no live session) leaves everything at full strength. See applyResearchState / civstudio:snapshot.
let knownSet = new Set();
const techKnown = type => knownSet.has(type);
// a building is unlocked once its WHOLE prereq expression (primary + every AND tech) is known
const buildingUnlocked = b =>
  knownSet.size === 0 || (knownSet.has(b.prereqTech) && (b.andTechs || []).every(p => knownSet.has(p)));
// a unit is unlocked by the same rule (units carry the same prereqTech/andTechs shape)
const unitUnlocked = u =>
  knownSet.size === 0 || (knownSet.has(u.prereqTech) && (u.andTechs || []).every(p => knownSet.has(p)));

// advisor → spine colour (muted, works in both themes) and → eos firm sector
const ADV_COLOR = {
  MILITARY: "#c0574c", ECONOMY: "#c8862a", GROWTH: "#5aa469",
  CULTURE: "#9b6cc0", RELIGION: "#4aa9a0", SCIENCE: "#4f8fce",
};
const ADV_SECTOR = {
  GROWTH: "Necessity", ECONOMY: "Capital", SCIENCE: "Export",
  CULTURE: "Enjoyment", RELIGION: "Enjoyment", MILITARY: "—",
};
// caravan role → spine colour (the unit-row analogue of ADV_COLOR for the building grid)
const ROLE_COLOR = {
  MILITARY: "#c0574c", SETTLER: "#5aa469", WORKER: "#c8862a", EXPLORER: "#4f8fce",
  TRADE: "#d0a53a", MISSIONARY: "#4aa9a0", HUNTER: "#9b7a4a", HEALER: "#c76b9a", COVERT: "#7a6ca0",
};
const roleColor = r => ROLE_COLOR[r] || "var(--ink-faint)";
// title-case a SCREAMING_SNAKE token for display (MILITARY → Military, FREED_SLAVE → Freed slave)
const titleCase = s => s ? s[0] + s.slice(1).toLowerCase().replace(/_/g, " ") : "—";
const ERAS = [
  ["C2C_ERA_PREHISTORIC", "Prehistoric"], ["C2C_ERA_ANCIENT", "Ancient"],
  ["C2C_ERA_CLASSICAL", "Classical"], ["C2C_ERA_MEDIEVAL", "Medieval"],
  ["C2C_ERA_RENAISSANCE", "Renaissance"], ["C2C_ERA_INDUSTRIAL", "Industrial"],
];
const ERA_NAME = Object.fromEntries(ERAS);

// COL_W = column pitch (doubled for wide, legible prereq elbows), ROW_H = row pitch (−25% for
// denser rows), CARD_H matched to the compacted card height (see .tech-node in styles.css).
const COL_W = 480, ROW_H = 48, PAD = 48, CARD_W = 215, CARD_H = 44;
const SHEET = "assets/tech/tech-icons.webp", SHEET_W = 1024, SHEET_H = 1216, ICON = 34;
const KMAX = 1.8, KSTEP = 1.2;   // min zoom is dynamic — see minZoom() (fit-to-height)

// building-icon sheet (build-buildings.mjs): 64² cells, 50 cols; each building carries an
// icon:[x,y,w,h] rect. Its natural size is read from the loaded image (rows depend on the count).
const BSHEET = "assets/buildings/building-icons.webp";
const bsheetImg = new Image();
bsheetImg.src = BSHEET;

// unit-icon sheets (build-units.mjs): 64² cells. Each unit carries an icon:[x,y,w,h] rect into
// USHEET; each functional UnitCombat class a category icon into UCSHEET. Natural sizes read lazily.
const USHEET = "assets/units/unit-icons.webp";
const usheetImg = new Image();
usheetImg.src = USHEET;
const UCSHEET = "assets/units/unit-combat-icons.webp";
const ucsheetImg = new Image();
ucsheetImg.src = UCSHEET;

const $ = id => document.getElementById(id);

let techs = null, byType = new Map(), parents = new Map();
let buildings = null, byTech = new Map();   // building set + prereqTech → its buildings
let units = null, byTechU = new Map(), combatsById = new Map();   // unit set + prereqTech → units; combat classes by id
let loaded = false, built = false;
let contentW = 0, contentH = 0, k = 1;
let eraEntryX = {};        // era key → its entry (min-gridX) column, for the era tabs
const nodeEl = new Map();  // type → card element
const edgeEls = [];        // {el, from, to}
let els = null;            // cached DOM handles, filled on first open

// --- data ------------------------------------------------------------------

function prereqList(t, group) {
  const g = t[group];
  if (!g) return [];
  const p = g.PrereqTech;
  if (p == null) return [];
  return Array.isArray(p) ? p : [p];
}
function allPrereqs(t) { return [...prereqList(t, "OrPreReqs"), ...prereqList(t, "AndPreReqs")]; }

// fetch a gzipped octet-stream pack and gunzip it in-page (the plots.pack/techs.pack contract),
// tolerating a host/CDN that already decompressed it via Content-Encoding (plain JSON then)
async function fetchGz(url) {
  const res = await fetch(apiUrl(url));
  if (!res.ok) throw new Error(`HTTP ${res.status} for ${url}`);
  const buf = await res.arrayBuffer();
  try {
    const stream = new Response(buf).body.pipeThrough(new DecompressionStream("gzip"));
    return JSON.parse(await new Response(stream).text());
  } catch {
    return JSON.parse(new TextDecoder().decode(buf));
  }
}

async function ensureLoaded() {
  if (loaded) return true;
  techs = await fetchGz("/api/techs");
  byType = new Map(techs.map(t => [t.Type, t]));
  for (const t of techs) parents.set(t.Type, allPrereqs(t).filter(p => byType.has(p)));
  // the buildings each tech unlocks (kept-tech-gated C2C set) — index by primary prereq tech, so
  // each node can draw its category-spectrum bar and, on focus, its building grid. A failed fetch
  // is non-fatal: the tree still works without buildings.
  try {
    buildings = await fetchGz("/api/buildings");
    byTech = new Map();
    for (const b of buildings) {
      if (!byTech.has(b.prereqTech)) byTech.set(b.prereqTech, []);
      byTech.get(b.prereqTech).push(b);
    }
  } catch (e) {
    console.error("tech tree: /api/buildings unavailable —", e.message);
    buildings = []; byTech = new Map();
  }
  // the units each tech unlocks (kept-tech-gated C2C land units) + the functional UnitCombat classes
  // — same index-by-primary-prereq pattern. Also non-fatal: the tree still works without units.
  try {
    const pack = await fetchGz("/api/units");
    units = pack.units || [];
    combatsById = new Map((pack.combats || []).map(c => [c.id, c]));
    byTechU = new Map();
    for (const u of units) {
      if (!byTechU.has(u.prereqTech)) byTechU.set(u.prereqTech, []);
      byTechU.get(u.prereqTech).push(u);
    }
  } catch (e) {
    console.error("tech tree: /api/units unavailable —", e.message);
    units = []; byTechU = new Map(); combatsById = new Map();
  }
  loaded = true;
  return true;
}

// the transitive prerequisite closure of a tech (its full ancestry), plus the tech itself
function ancestry(type) {
  const seen = new Set([type]), stack = [type];
  while (stack.length) {
    for (const p of parents.get(stack.pop()) || [])
      if (!seen.has(p)) { seen.add(p); stack.push(p); }
  }
  return seen;
}

// --- geometry --------------------------------------------------------------

// research cost, shown with its beaker icon. CivStudio will have three beaker types —
// blue (science, the default), red (converted from hammers) and yellow (race-specific);
// the human common tree is all blue. data-beaker lets the other two slot in later as
// hue variants without touching this call site.
function beakerCost(t) {
  const beaker = t.beaker || "blue";   // per-tech currency (naval → green); default blue science
  return `<span class="tech-bk" data-beaker="${beaker}" aria-hidden="true"></span>${(+t.iCost).toLocaleString()}`;
}

const gx = t => +t.iGridX, gy = t => +t.iGridY;
const nodeX = t => PAD + gx(t) * COL_W;
const nodeY = t => PAD + (gy(t) - 1) * ROW_H;
const advOf = t => ADV_COLOR[(t.Advisor || "").replace("ADVISOR_", "")] || "var(--ink-faint)";
const catColor = c => ADV_COLOR[c] || "var(--ink-faint)";   // building category (Advisor enum) → colour

// a thin category-spectrum bar summarising the buildings a tech unlocks — segments coloured by
// advisor category, width ∝ count. This is the overview LOD: every node with buildings shows it,
// and the focused node additionally expands to the real building grid (see buildingGrid).
function spectrumBar(type) {
  const bl = byTech.get(type);
  if (!bl || !bl.length) return null;
  const byCat = new Map();
  for (const b of bl) { const c = b.category || "OTHER"; byCat.set(c, (byCat.get(c) || 0) + 1); }
  const spec = document.createElement("div");
  spec.className = "tech-spec";
  spec.title = `${bl.length} building${bl.length > 1 ? "s" : ""}`;
  for (const [c, n] of [...byCat].sort((a, b) => b[1] - a[1])) {
    const seg = document.createElement("span");
    seg.style.flexGrow = String(n);
    seg.style.background = catColor(c);
    spec.appendChild(seg);
  }
  return spec;
}

// the unit analogue of spectrumBar: a thin role-spectrum bar summarising the units a tech unlocks,
// segments coloured by caravan role, width ∝ count. Sits just above the building bar (see .tech-uspec).
function unitSpectrumBar(type) {
  const ul = byTechU.get(type);
  if (!ul || !ul.length) return null;
  const byRole = new Map();
  for (const u of ul) { const r = u.caravanRole || "MILITARY"; byRole.set(r, (byRole.get(r) || 0) + 1); }
  const spec = document.createElement("div");
  spec.className = "tech-spec tech-uspec";
  spec.title = `${ul.length} unit${ul.length > 1 ? "s" : ""}`;
  for (const [r, n] of [...byRole].sort((a, b) => b[1] - a[1])) {
    const seg = document.createElement("span");
    seg.style.flexGrow = String(n);
    seg.style.background = roleColor(r);
    spec.appendChild(seg);
  }
  return spec;
}

// --- build (once) ----------------------------------------------------------

function build() {
  if (built) return;
  const canvas = els.canvas, edges = els.edges;
  let maxX = 0, maxY = 0;
  const perEra = {};
  for (const t of techs) {
    maxX = Math.max(maxX, gx(t)); maxY = Math.max(maxY, gy(t));
    (perEra[t.Era] ||= []).push(gx(t));
  }
  contentW = PAD * 2 + (maxX + 1) * COL_W;
  contentH = PAD * 2 + maxY * ROW_H;

  // era strata: contiguous vertical bands from each era's first column to the next's
  const present = ERAS.filter(([key]) => perEra[key]);
  present.forEach(([key, name], i) => {
    const startX = Math.min(...perEra[key]);
    eraEntryX[key] = startX;
    const left = PAD + startX * COL_W - (COL_W - CARD_W) / 2 - 8;
    const nextStart = i + 1 < present.length
      ? Math.min(...perEra[present[i + 1][0]]) : maxX + 1;
    const right = PAD + nextStart * COL_W - (COL_W - CARD_W) / 2 - 8;
    const band = document.createElement("div");
    band.className = "tech-band";
    band.style.left = left + "px";
    band.style.width = (right - left) + "px";
    band.style.height = contentH + "px";
    band.innerHTML = `<div class="tech-band-label">${name}</div>`;
    canvas.appendChild(band);
  });

  // prerequisite elbows (behind the cards)
  edges.setAttribute("width", contentW);
  edges.setAttribute("height", contentH);
  edges.setAttribute("viewBox", `0 0 ${contentW} ${contentH}`);
  const NS = "http://www.w3.org/2000/svg";
  for (const t of techs) {
    const x2 = nodeX(t), y2 = nodeY(t) + CARD_H / 2;
    const draw1 = (from, isOr) => {
      const f = byType.get(from); if (!f) return;
      const x1 = nodeX(f) + CARD_W, y1 = nodeY(f) + CARD_H / 2;
      const mx = (x1 + x2) / 2;
      const path = document.createElementNS(NS, "path");
      path.setAttribute("d", `M${x1},${y1} C${mx},${y1} ${mx},${y2} ${x2},${y2}`);
      if (isOr) path.classList.add("oredge");
      edges.appendChild(path);
      edgeEls.push({ el: path, from, to: t.Type });
    };
    prereqList(t, "AndPreReqs").forEach(p => draw1(p, false));
    prereqList(t, "OrPreReqs").forEach(p => draw1(p, true));
  }

  // cards
  const scale = ICON / 64;
  for (const t of techs) {
    const el = document.createElement("div");
    el.className = "tech-node";
    el.style.left = nodeX(t) + "px";
    el.style.top = nodeY(t) + "px";
    el.style.setProperty("--adv", advOf(t));
    el.dataset.type = t.Type;

    const ico = document.createElement("div");
    ico.className = "tech-ico";
    if (t.icon) {
      const [ix, iy] = t.icon;
      ico.style.backgroundImage = `url(${SHEET})`;
      ico.style.backgroundSize = `${SHEET_W * scale}px ${SHEET_H * scale}px`;
      ico.style.backgroundPosition = `${-ix * scale}px ${-iy * scale}px`;
    } else {
      ico.classList.add("chip");
      ico.textContent = (t.name || t.Type.replace("TECH_", ""))[0];
    }
    const tx = document.createElement("div");
    tx.className = "tech-tx";
    tx.innerHTML = `<div class="tech-nm"></div><div class="tech-ct">${beakerCost(t)}</div>`;
    tx.querySelector(".tech-nm").textContent = t.name || t.Type.replace("TECH_", "");

    el.append(ico, tx);
    const spec = spectrumBar(t.Type);
    if (spec) el.appendChild(spec);
    const uspec = unitSpectrumBar(t.Type);
    if (uspec) el.appendChild(uspec);
    el.addEventListener("mouseenter", () => highlight(t.Type));
    el.addEventListener("mouseleave", clearHighlight);
    el.addEventListener("click", () => select(t.Type));
    canvas.appendChild(el);
    nodeEl.set(t.Type, el);
  }
  built = true;
}

// --- signature: ancestry highlight -----------------------------------------

function highlight(type) {
  const lit = ancestry(type);
  els.canvas.classList.add("dim");
  els.edges.classList.add("dim");
  for (const [ty, el] of nodeEl) {
    el.classList.toggle("lit", lit.has(ty));
    el.classList.toggle("root", ty === type);
  }
  for (const e of edgeEls) e.el.classList.toggle("lit", lit.has(e.from) && lit.has(e.to));
}
function clearHighlight() {
  els.canvas.classList.remove("dim");
  els.edges.classList.remove("dim");
  for (const el of nodeEl.values()) el.classList.remove("lit", "root");
  for (const e of edgeEls) e.el.classList.remove("lit");
}

// --- detail panel ----------------------------------------------------------

function select(type) {
  const t = byType.get(type);
  if (!t) return;
  for (const el of nodeEl.values()) el.classList.remove("sel");
  nodeEl.get(type)?.classList.add("sel");
  renderTechRail(t);
}

// open the shared right rail with #rail content (the tree is a map-mode, so it reuses the
// province/advisor rail). The live HUD that used to have to be hidden here is gone — every mode now
// puts its content in #rail, so there is nothing left to mask it.
function openRail() {
  showRail(true);
  return railEl();
}

// the selected tech's detail + the building grid (the focus LOD: spectrum bar on the node,
// full grid here). Clicking a building opens its inspector (showBuildingRail).
function renderTechRail(t) {
  const adv = (t.Advisor || "").replace("ADVISOR_", "");
  const sector = ADV_SECTOR[adv] || "—";
  const pre = allPrereqs(t);
  const bl = byTech.get(t.Type) || [];
  const ul = byTechU.get(t.Type) || [];
  const r = openRail();
  r.innerHTML = `<div class="detail tech-sheet" style="--adv:${advOf(t)}">
    <div class="tech-d-era">${ERA_NAME[t.Era] || ""}</div>
    <h2 class="tech-d-name"></h2>
    <div class="tech-d-meta">
      <span class="tech-d-tag">${beakerCost(t)}&nbsp;beakers</span>
      <span class="tech-d-tag">${adv ? adv[0] + adv.slice(1).toLowerCase() : "—"}</span>
      <span class="tech-d-tag">Sector: <b>${sector}</b></span>
    </div>
    ${bl.length ? `<div class="tech-d-h">Unlocks ${bl.length} building${bl.length > 1 ? "s" : ""}</div><div class="tech-grid"></div>` : ""}
    ${ul.length ? `<div class="tech-d-h">Unlocks ${ul.length} unit${ul.length > 1 ? "s" : ""}</div><div class="tech-ugrid"></div>` : ""}
    ${pre.length ? `<div class="tech-d-h">Requires</div><div class="tech-d-pre"></div>` : ""}
    ${t.help ? `<div class="tech-d-h">Overview</div><div class="tech-d-help"></div>` : ""}
    ${t.quote ? `<div class="tech-d-quote"></div>` : ""}
  </div>`;
  r.querySelector(".tech-d-name").textContent = t.name || t.Type;
  if (t.help) r.querySelector(".tech-d-help").textContent = cleanText(t.help);
  if (t.quote) r.querySelector(".tech-d-quote").textContent = cleanText(t.quote);
  const preBox = r.querySelector(".tech-d-pre");
  if (preBox) {
    const mk = (p, kind) => {
      const f = byType.get(p);
      const b = document.createElement("button");
      b.className = "tech-d-prereq";
      b.innerHTML = `${f ? (f.name || p) : p}<span class="k">${kind}</span>`;
      b.addEventListener("click", () => { select(p); scrollToType(p); });
      preBox.appendChild(b);
    };
    prereqList(t, "AndPreReqs").forEach(p => mk(p, "and"));
    prereqList(t, "OrPreReqs").forEach(p => mk(p, "or"));
  }
  const grid = r.querySelector(".tech-grid");
  if (grid) fillGrid(grid, bl, t);
  const ugrid = r.querySelector(".tech-ugrid");
  if (ugrid) fillUnitGrid(ugrid, ul, t);
}

// the building sheet's natural size (rows depend on the count; falls back until the image loads)
const sheetDims = () => [bsheetImg.naturalWidth || 3200, bsheetImg.naturalHeight || 1344];

// a single building icon cell: the real C2C button from the sheet (or a category-colour chip when
// the art is missing), framed in a category-tinted backing so the varied art reads as one set.
function buildingCell(b) {
  const cell = document.createElement("button");
  cell.className = "tech-bcell";
  cell.style.setProperty("--cat", catColor(b.category || "OTHER"));
  cell.title = b.name || b.id.replace("BUILDING_", "");
  if (b.icon) {
    const [x, y] = b.icon, [bw, bh] = sheetDims(), s = 26 / 64;   // cells render ~26px
    cell.style.backgroundImage = `url(${BSHEET})`;
    cell.style.backgroundSize = `${bw * s}px ${bh * s}px`;
    cell.style.backgroundPosition = `${-x * s}px ${-y * s}px`;
  } else {
    cell.classList.add("chip");
    cell.textContent = (b.name || b.id.replace("BUILDING_", ""))[0];
  }
  cell.classList.toggle("locked", !buildingUnlocked(b));
  cell.addEventListener("click", ev => { ev.stopPropagation(); showBuildingRail(b); });
  return cell;
}

// fill a rail grid with a tech's buildings, grouped by category (so similar buildings sit together)
function fillGrid(grid, bl, tech) {
  grid.dataset.tech = tech.Type;
  const byCat = new Map();
  for (const b of bl) { const c = b.category || "OTHER"; (byCat.get(c) || byCat.set(c, []).get(c)).push(b); }
  const order = [...byCat.keys()].sort((a, b) => byCat.get(b).length - byCat.get(a).length);
  for (const c of order) {
    const group = document.createElement("div");
    group.className = "tech-bgroup";
    group.style.setProperty("--cat", catColor(c));
    for (const b of byCat.get(c)) group.appendChild(buildingCell(b));
    grid.appendChild(group);
  }
}

// the unit sheets' natural sizes (rows depend on the count; fall back until the images load)
const usheetDims = () => [usheetImg.naturalWidth || 3200, usheetImg.naturalHeight || 384];
const ucsheetDims = () => [ucsheetImg.naturalWidth || 3200, ucsheetImg.naturalHeight || 64];

// a single unit icon cell: the real C2C button (or a role-colour chip when the art is missing),
// framed in a role-tinted backing. Reuses the building cell's classes (--cat drives the colour).
function unitCell(u) {
  const cell = document.createElement("button");
  cell.className = "tech-bcell tech-ucell";
  cell.style.setProperty("--cat", roleColor(u.caravanRole || "MILITARY"));
  cell.title = u.name || u.id.replace("UNIT_", "");
  if (u.icon) {
    const [x, y] = u.icon, [bw, bh] = usheetDims(), s = 26 / 64;   // cells render ~26px
    cell.style.backgroundImage = `url(${USHEET})`;
    cell.style.backgroundSize = `${bw * s}px ${bh * s}px`;
    cell.style.backgroundPosition = `${-x * s}px ${-y * s}px`;
  } else {
    cell.classList.add("chip");
    cell.textContent = (u.name || u.id.replace("UNIT_", ""))[0];
  }
  cell.classList.toggle("locked", !unitUnlocked(u));
  cell.addEventListener("click", ev => { ev.stopPropagation(); showUnitRail(u); });
  return cell;
}

// fill a rail grid with a tech's units, grouped by caravan role (like buildings by category)
function fillUnitGrid(grid, ul, tech) {
  grid.dataset.tech = tech.Type;
  const byRole = new Map();
  for (const u of ul) { const r = u.caravanRole || "MILITARY"; (byRole.get(r) || byRole.set(r, []).get(r)).push(u); }
  const order = [...byRole.keys()].sort((a, b) => byRole.get(b).length - byRole.get(a).length);
  for (const r of order) {
    const group = document.createElement("div");
    group.className = "tech-bgroup";
    group.style.setProperty("--cat", roleColor(r));
    for (const u of byRole.get(r)) group.appendChild(unitCell(u));
    grid.appendChild(group);
  }
}

// the building inspector: the C2C button art (large), name, category, prereqs and the pedia text —
// rendered in the rail. A back arrow returns to the unlocking tech's detail. Any building is
// inspectable, even locked ones, so the tree doubles as a reference of everything ahead.
function showBuildingRail(b) {
  const cat = b.category || "OTHER";
  const tech = byType.get(b.prereqTech);
  const ands = (b.andTechs || []).map(p => byType.get(p) ? (byType.get(p).name || p) : p);
  const r = openRail();
  r.innerHTML = `<div class="detail tech-sheet building-sheet" style="--adv:${catColor(cat)}">
    <button class="bld-back" data-bld-back>&larr; ${tech ? (tech.name || b.prereqTech) : "Tech"}</button>
    <div class="bld-head">
      <div class="bld-art"></div>
      <div class="bld-id">
        <h2 class="tech-d-name"></h2>
        <span class="tech-d-tag">${cat[0] + cat.slice(1).toLowerCase()}</span>
      </div>
    </div>
    ${b.pedia ? `<div class="tech-d-h">About</div><div class="tech-d-help"></div>` : ""}
    <div class="tech-d-h">Unlocked by</div><div class="tech-d-pre"></div>
  </div>`;
  r.querySelector(".tech-d-name").textContent = b.name || b.id.replace("BUILDING_", "");
  if (b.pedia) r.querySelector(".tech-d-help").textContent = cleanText(b.pedia);
  // large art (the button icon at ~72px, or a category chip)
  const art = r.querySelector(".bld-art");
  if (b.icon) {
    const [x, y] = b.icon, [bw, bh] = sheetDims(), s = 72 / 64;
    art.style.backgroundImage = `url(${BSHEET})`;
    art.style.backgroundSize = `${bw * s}px ${bh * s}px`;
    art.style.backgroundPosition = `${-x * s}px ${-y * s}px`;
  } else {
    art.classList.add("chip");
    art.textContent = (b.name || b.id.replace("BUILDING_", ""))[0];
  }
  // prereqs: the primary unlocking tech (click → jump to it) then any AND-required techs
  const preBox = r.querySelector(".tech-d-pre");
  const mk = (label, type, kind) => {
    const btn = document.createElement("button");
    btn.className = "tech-d-prereq";
    btn.innerHTML = `${label}<span class="k">${kind}</span>`;
    if (type && byType.get(type)) btn.addEventListener("click", () => { select(type); scrollToType(type); });
    preBox.appendChild(btn);
  };
  mk(tech ? (tech.name || b.prereqTech) : b.prereqTech, b.prereqTech, "tech");
  (b.andTechs || []).forEach((p, i) => mk(ands[i], p, "and"));
  r.querySelector("[data-bld-back]")?.addEventListener("click", () => { if (tech) select(tech.Type); });
}

// the unit inspector: large C2C button art, name, caravan role, march stats (moves/combat/cost),
// its UnitCombat class (category icon + folded signature skill), prereqs and the pedia — in the rail.
// A back arrow returns to the unlocking tech. Any unit is inspectable, even locked ones.
function showUnitRail(u) {
  const role = u.caravanRole || "MILITARY";
  const tech = byType.get(u.prereqTech);
  const ands = (u.andTechs || []).map(p => byType.get(p) ? (byType.get(p).name || p) : p);
  const combat = combatsById.get(u.combatClass);
  const stats = [];
  if (u.iMoves != null) stats.push(`${u.iMoves} move${u.iMoves === 1 ? "" : "s"}`);
  if (u.iCombat != null && u.iCombat > 0) stats.push(`${u.iCombat} combat`);
  if (u.iCost != null && u.iCost > 0) stats.push(`${u.iCost} hammers`);
  if (u.obsoleteTech) stats.push(`obsolete: ${byType.get(u.obsoleteTech)?.name || u.obsoleteTech.replace("TECH_", "")}`);
  const r = openRail();
  r.innerHTML = `<div class="detail tech-sheet building-sheet unit-sheet" style="--adv:${roleColor(role)}">
    <button class="bld-back" data-bld-back>&larr; ${tech ? (tech.name || u.prereqTech) : "Tech"}</button>
    <div class="bld-head">
      <div class="bld-art"></div>
      <div class="bld-id">
        <h2 class="tech-d-name"></h2>
        <span class="tech-d-tag">${titleCase(role)}${u.special ? " · " + titleCase(u.special) : ""}</span>
      </div>
    </div>
    ${stats.length ? `<div class="tech-d-meta">${stats.map(s => `<span class="tech-d-tag">${s}</span>`).join("")}</div>` : ""}
    ${combat ? `<div class="tech-d-h">Class</div><div class="unit-class"></div>` : ""}
    ${u.pedia ? `<div class="tech-d-h">About</div><div class="tech-d-help"></div>` : ""}
    <div class="tech-d-h">Unlocked by</div><div class="tech-d-pre"></div>
  </div>`;
  r.querySelector(".tech-d-name").textContent = u.name || u.id.replace("UNIT_", "");
  if (u.pedia) r.querySelector(".tech-d-help").textContent = cleanText(u.pedia);
  // large art (the button icon at ~72px, or a role chip)
  const art = r.querySelector(".bld-art");
  if (u.icon) {
    const [x, y] = u.icon, [bw, bh] = usheetDims(), s = 72 / 64;
    art.style.backgroundImage = `url(${USHEET})`;
    art.style.backgroundSize = `${bw * s}px ${bh * s}px`;
    art.style.backgroundPosition = `${-x * s}px ${-y * s}px`;
  } else {
    art.classList.add("chip");
    art.textContent = (u.name || u.id.replace("UNIT_", ""))[0];
  }
  // the UnitCombat class: category icon + name + the signature skill acting in the role trains
  const cl = r.querySelector(".unit-class");
  if (cl && combat) {
    const ico = document.createElement("span");
    ico.className = "unit-class-ico";
    if (combat.icon) {
      const [x, y] = combat.icon, [bw, bh] = ucsheetDims(), s = 20 / 64;
      ico.style.backgroundImage = `url(${UCSHEET})`;
      ico.style.backgroundSize = `${bw * s}px ${bh * s}px`;
      ico.style.backgroundPosition = `${-x * s}px ${-y * s}px`;
    }
    const lbl = document.createElement("span");
    lbl.textContent = (combat.name || u.combatClass.replace("UNITCOMBAT_", ""))
      + (combat.signatureSkill ? ` · trains ${titleCase(combat.signatureSkill)}` : "");
    cl.append(ico, lbl);
  }
  // prereqs: the primary unlocking tech (click → jump) then any AND-required techs
  const preBox = r.querySelector(".tech-d-pre");
  const mk = (label, type, kind) => {
    const btn = document.createElement("button");
    btn.className = "tech-d-prereq";
    btn.innerHTML = `${label}<span class="k">${kind}</span>`;
    if (type && byType.get(type)) btn.addEventListener("click", () => { select(type); scrollToType(type); });
    preBox.appendChild(btn);
  };
  mk(tech ? (tech.name || u.prereqTech) : u.prereqTech, u.prereqTech, "tech");
  (u.andTechs || []).forEach((p, i) => mk(ands[i], p, "and"));
  r.querySelector("[data-bld-back]")?.addEventListener("click", () => { if (tech) select(tech.Type); });
}

// strip the Civ4 pedia markup ([NEWLINE], [PARAGRAPH:n], [ICON_*], colour tags) to plain text
function cleanText(s) {
  return s.replace(/\[PARAGRAPH:\d+\]/g, "\n\n").replace(/\[NEWLINE\]/g, "\n")
    .replace(/\[\/?COLOR[^\]]*\]/g, "").replace(/\[ICON_[^\]]*\]/g, "").replace(/\[[^\]]*\]/g, "")
    .replace(/\n{3,}/g, "\n\n").trim();
}

// --- zoom & pan ------------------------------------------------------------

function applyZoom(anchor) {
  const vp = els.viewport;
  // keep the anchor point (viewport-relative) fixed across the scale change
  const ax = anchor ? anchor.x : vp.clientWidth / 2;
  const ay = anchor ? anchor.y : vp.clientHeight / 2;
  const cx = (vp.scrollLeft + ax) / (els._k || 1);
  const cy = (vp.scrollTop + ay) / (els._k || 1);
  els.sizer.style.width = contentW * k + "px";
  els.sizer.style.height = contentH * k + "px";
  els.canvas.style.transform = `scale(${k})`;
  vp.scrollLeft = cx * k - ax;
  vp.scrollTop = cy * k - ay;
  els._k = k;
}
// refresh the researched-state styling from the live snapshot. Three states, matching the top bar's
// research fill so the pill and the tree read as the same object (docs/zoom-bands.md):
//   known      — solid, filled node
//   locked     — outline only: an EMPTY node, i.e. an unfilled bar
//   researching — the one node filling right now, its --research fraction driving a partial fill
// A no-op with no live known set (browsing the tree with no session: everything reads as available).
function applyResearchState() {
  knownSet = new Set(liveKnownTechs());
  const live = knownSet.size > 0;
  const r = liveResearch();
  for (const [ty, el] of nodeEl) {
    // `researched` arms the fill language at all; with no live colony the tree is just a reference
    // work and every node stays a plain card (filling them all in would assert a progress that the
    // page has no basis for).
    el.classList.toggle("researched", live);
    if (!live) {
      el.classList.remove("locked", "researching");
      el.style.removeProperty("--research");
      continue;
    }
    const known = techKnown(ty), active = !!r && r.type === ty;
    el.classList.toggle("locked", !known);
    el.classList.toggle("researching", active);
    el.style.setProperty("--research", known ? "1" : active ? r.progress.toFixed(3) : "0");
  }
}

// the smallest zoom that still fills the viewport height: you can zoom out until the
// whole tree fits vertically, but no further (no dead space below the tree)
function minZoom() {
  const vh = els && els.viewport ? els.viewport.clientHeight : 0;
  return (vh && contentH) ? Math.min(1, vh / contentH) : 0.4;
}
function zoomBy(f, anchor) {
  k = Math.min(KMAX, Math.max(minZoom(), k * f));
  applyZoom(anchor);
}

function scrollToType(type) {
  const t = byType.get(type); if (!t) return;
  els.viewport.scrollTo({ left: nodeX(t) * k - 60, behavior: "smooth" });
}
// bring a node to the centre of the viewport (both axes) — used by the search box
function centerOnType(type) {
  const t = byType.get(type); if (!t) return;
  const vp = els.viewport;
  vp.scrollTo({
    left: (nodeX(t) + CARD_W / 2) * k - vp.clientWidth / 2,
    top: (nodeY(t) + CARD_H / 2) * k - vp.clientHeight / 2,
    behavior: "smooth",
  });
}
function scrollToEra(key) {
  const x = eraEntryX[key]; if (x == null) return;
  els.viewport.scrollTo({ left: (PAD + x * COL_W) * k - 40, behavior: "smooth" });
}

// mark the era tab whose band the viewport's left edge currently sits in
function syncEraTab() {
  const leftCol = (els.viewport.scrollLeft / k - PAD) / COL_W;
  let active = ERAS.find(([key]) => eraEntryX[key] != null)?.[0];
  for (const [key] of ERAS) if (eraEntryX[key] != null && eraEntryX[key] <= leftCol + 1) active = key;
  for (const b of els.eras.children) b.setAttribute("aria-pressed", String(b.dataset.era === active));
}

// --- open / close ----------------------------------------------------------

function buildEraTabs() {
  els.eras.innerHTML = "";
  for (const [key, name] of ERAS) {
    if (eraEntryX[key] == null) continue;
    const b = document.createElement("button");
    b.textContent = name;
    b.dataset.era = key;
    b.setAttribute("aria-pressed", "false");
    b.addEventListener("click", () => scrollToEra(key));
    els.eras.appendChild(b);
  }
}

async function open(focusType) {
  if (S.techOpen) return;
  cacheEls();
  els.modal.hidden = false;      // reveal the shell (also makes viewport measurable)
  S.techOpen = true;
  pausePlayback();               // modals always run in paused mode
  try {
    await ensureLoaded();
    build();
    buildEraTabs();
    applyResearchState();         // dim what the live colony has not unlocked (if spectating)
    k = Math.max(k, minZoom());   // never open zoomed out past the fit-to-height floor
    applyZoom();
    syncEraTab();
    // Land on the tech we were asked to focus (the top bar's research pill sends the one being
    // researched). applyZoom just wrote the scroll position, so centring has to come after it, and
    // selecting lights the node + its prereq path and fills the rail sheet — the "where am I in the
    // tree" answer the pill click is really asking for.
    if (focusType && byType.has(focusType)) {
      select(focusType);
      centerOnType(focusType);
    }
  } catch (e) {
    els.viewport.innerHTML = `<div style="padding:40px;color:var(--ink-soft);max-width:520px">
      Couldn't load the tech data. The tree reads <code>/api/techs</code> from the spectator
      server — check the server is reachable. <br><br>${e.message}</div>`;
  }
}
function close() {
  if (!S.techOpen) return;
  S.techOpen = false;
  els.modal.hidden = true;
  for (const el of nodeEl.values()) el.classList.remove("sel");
  clearHighlight();
  renderRail();   // restore the province / world / live rail the tree borrowed
  draw();         // repaint the map that was frozen behind the tree
}

function cacheEls() {
  if (els) return;
  els = {
    modal: $("techModal"), viewport: $("techViewport"), sizer: $("techSizer"),
    canvas: $("techCanvas"), edges: $("techEdges"), eras: $("techEras"),
    _k: 1,
  };
}

/** Open the tree if closed, close it if open — the F7 shortcut (see shortcuts.mjs). */
export function toggleTech() { S.techOpen ? close() : open(); }

/** Open the tech view idempotently — the Technology advisor's entry point (advisors.mjs).
 *  `focusType` (optional): a tech id to select and centre once the tree is built — the research pill
 *  passes the tech being researched, so clicking it lands on that node rather than the era-0 origin. */
export function openTech(focusType) { if (!S.techOpen) open(focusType); }

/** Close the tree if open — the Escape shortcut while the modal is up. */
export function closeTech() { close(); }

// --- unified search hooks (§5): the top-bar search box delegates to these while the Technology
// advisor is active, so techs and provinces share one box. Same corpus/row/pick as the (now-hidden)
// in-tree search. techMatches returns up to 12 tech objects for a query. ---
export function techMatches(q) {
  if (!techs) return [];
  q = q.toLowerCase();
  const scored = [];
  for (const t of techs) {
    const nm = (t.name || t.Type.replace("TECH_", "")).toLowerCase();
    let score = -1;
    if (nm === q) score = 100; else if (nm.startsWith(q)) score = 70; else if (nm.includes(q)) score = 40;
    else if (t.Type.toLowerCase().includes(q)) score = 20;
    if (score >= 0) scored.push({ t, score });
  }
  scored.sort((a, b) => b.score - a.score || (+a.t.iCost) - (+b.t.iCost) || (a.t.name || "").localeCompare(b.t.name || ""));
  return scored.slice(0, 12).map(s => s.t);
}
export function techRowHtml(t, i, active) {
  const nm = t.name || t.Type.replace("TECH_", "");
  return `<div class="search-row${active ? " active" : ""}" role="option" data-i="${i}">
    <span class="sr-name">${nm}</span><span class="sr-meta">${ERA_NAME[t.Era] || ""}</span></div>`;
}
/** Select + centre a tech from a search pick (the tech object). */
export function pickTech(t) { select(t.Type); centerOnType(t.Type); }

// --- unified tech+building search (§4a): one interleaved ranked list, a kind chip per row -------
const clean = s => s.toLowerCase();
function scoreName(name, id, q, base) {
  const nm = clean(name);
  if (nm === q) return base; if (nm.startsWith(q)) return base - 30;
  if (nm.includes(q)) return base - 60; if (clean(id).includes(q)) return base - 80;
  return -1;
}
/** Match techs AND buildings by name/id (case-insensitive), interleaved and ranked, ≤14 rows. */
export function techBuildingMatches(q) {
  if (!q) return [];
  q = clean(q);
  const scored = [];
  for (const t of techs || []) {
    const s = scoreName(t.name || t.Type.replace("TECH_", ""), t.Type, q, 100);
    if (s >= 0) scored.push({ kind: "tech", t, name: t.name || t.Type, score: s });
  }
  for (const b of buildings || []) {
    const s = scoreName(b.name || b.id.replace("BUILDING_", ""), b.id, q, 95);
    if (s >= 0) scored.push({ kind: "building", b, name: b.name || b.id, score: s });
  }
  for (const u of units || []) {
    const s = scoreName(u.name || u.id.replace("UNIT_", ""), u.id, q, 90);
    if (s >= 0) scored.push({ kind: "unit", u, name: u.name || u.id, score: s });
  }
  scored.sort((a, b) => b.score - a.score || a.name.localeCompare(b.name));
  return scored.slice(0, 14);
}
export function searchRowHtml(m, i, active) {
  let nm, meta, chip;
  if (m.kind === "tech") {
    nm = m.t.name || m.t.Type.replace("TECH_", ""); meta = ERA_NAME[m.t.Era] || ""; chip = "Tech";
  } else if (m.kind === "building") {
    nm = m.b.name || m.b.id.replace("BUILDING_", "");
    meta = m.b.category ? titleCase(m.b.category) : ""; chip = "Bldg";
  } else {
    nm = m.u.name || m.u.id.replace("UNIT_", ""); meta = titleCase(m.u.caravanRole); chip = "Unit";
  }
  return `<div class="search-row${active ? " active" : ""}" role="option" data-i="${i}">
    <span class="sr-kind sr-kind-${m.kind}">${chip}</span>
    <span class="sr-name">${nm}</span><span class="sr-meta">${meta}</span></div>`;
}
/** Act on a unified search pick: a tech centres its node; a building/unit jumps to its tech + inspects it. */
export function pickSearchResult(m) {
  if (m.kind === "tech") { select(m.t.Type); centerOnType(m.t.Type); return; }
  const row = m.kind === "building" ? m.b : m.u;
  const t = byType.get(row.prereqTech);
  if (t) {
    for (const el of nodeEl.values()) el.classList.remove("sel");
    nodeEl.get(t.Type)?.classList.add("sel");
    centerOnType(t.Type);
  }
  if (m.kind === "building") showBuildingRail(m.b); else showUnitRail(m.u);
}

export function initTechTree() {
  // the standalone tech button was retired (the Technology advisor is the entry point); wire it
  // only if present. The rest of the init must still run so the Technology advisor works.
  const btn = $("techBtn");
  if (btn) btn.addEventListener("click", open);
  // F6 (enter the advisor) and Escape (→ mainmap) are dispatched centrally by js/shortcuts.mjs,
  // which calls openTech() / closeTech(). The tree has no close button of its own now (it is a
  // map-mode); leaving the advisor closes it.
  $("techZoomIn").addEventListener("click", () => zoomBy(KSTEP));
  $("techZoomOut").addEventListener("click", () => zoomBy(1 / KSTEP));

  // The tree's own header search was retired with the modal chrome; the unified top-bar search
  // box (panel.mjs) delegates to the exported techMatches/techRowHtml/pickTech while the
  // Technology advisor is active, so techs and provinces share one box.
  const vp = $("techViewport");
  vp.addEventListener("scroll", syncEraTab, { passive: true });
  // a fresh live snapshot may change the colony's known techs — re-dim while the tree is open
  window.addEventListener("civstudio:snapshot", () => { if (S.techOpen && built) applyResearchState(); });

  // drag anywhere on the canvas to pan (grab cursor); a drag that moved swallows the
  // click so it doesn't also select the node under the pointer
  let dragging = false, sx = 0, sy = 0, sl = 0, st = 0, moved = false;
  vp.addEventListener("mousedown", e => {
    if (e.button !== 0) return;
    dragging = true; moved = false;
    sx = e.clientX; sy = e.clientY; sl = vp.scrollLeft; st = vp.scrollTop;
    vp.classList.add("grabbing");
  });
  window.addEventListener("mousemove", e => {
    if (!dragging) return;
    const dx = e.clientX - sx, dy = e.clientY - sy;
    if (Math.abs(dx) + Math.abs(dy) > 3) moved = true;
    vp.scrollLeft = sl - dx; vp.scrollTop = st - dy;
  });
  window.addEventListener("mouseup", () => {
    if (dragging) { dragging = false; vp.classList.remove("grabbing"); }
  });
  vp.addEventListener("click", e => {
    if (moved) { e.stopPropagation(); e.preventDefault(); moved = false; }
  }, true);   // capture: kill the click that ends a drag before it reaches a node
  // ctrl/⌘ + wheel (or trackpad pinch) zooms at the cursor; plain wheel scrolls natively
  vp.addEventListener("wheel", e => {
    if (!(e.ctrlKey || e.metaKey)) return;
    e.preventDefault();
    const r = vp.getBoundingClientRect();
    zoomBy(e.deltaY < 0 ? KSTEP : 1 / KSTEP, { x: e.clientX - r.left, y: e.clientY - r.top });
  }, { passive: false });
}
