// The technology tree — a full-screen modal over the map. Self-contained: it owns its
// DOM (the #techModal markup in index.html), loads its data lazily on first open
// (GET /api/techs from the spectator server, gunzipped in-page via DecompressionStream —
// the plots.mjs pattern), and while it is up the map's paint() bails (S.techOpen), so
// nothing renders behind it. Esc or a click outside closes it and repaints the map.
//
// Layout is driven by the C2C grid: iGridX is the horizontal era-timeline column,
// iGridY the vertical lane (see docs/tech-tree.md). Cards sit on that lattice, hairline
// SVG elbows draw the prerequisites (solid = AND, dashed = OR), and hovering a tech
// lights its whole prerequisite chain in gold while the rest dims.
import { S, apiUrl } from "./core.mjs";
import { draw } from "./main.mjs";
import { pausePlayback } from "./panel.mjs";
import { createSearchBox } from "./searchbox.mjs";

// advisor → spine colour (muted, works in both themes) and → eos firm sector
const ADV_COLOR = {
  MILITARY: "#c0574c", ECONOMY: "#c8862a", GROWTH: "#5aa469",
  CULTURE: "#9b6cc0", RELIGION: "#4aa9a0", SCIENCE: "#4f8fce",
};
const ADV_SECTOR = {
  GROWTH: "Necessity", ECONOMY: "Capital", SCIENCE: "Export",
  CULTURE: "Enjoyment", RELIGION: "Enjoyment", MILITARY: "—",
};
const ERAS = [
  ["C2C_ERA_PREHISTORIC", "Prehistoric"], ["C2C_ERA_ANCIENT", "Ancient"],
  ["C2C_ERA_CLASSICAL", "Classical"], ["C2C_ERA_MEDIEVAL", "Medieval"],
  ["C2C_ERA_RENAISSANCE", "Renaissance"], ["C2C_ERA_INDUSTRIAL", "Industrial"],
];
const ERA_NAME = Object.fromEntries(ERAS);

const COL_W = 240, ROW_H = 64, PAD = 48, CARD_W = 215, CARD_H = 56;
const SHEET = "assets/tech/tech-icons.webp", SHEET_W = 1024, SHEET_H = 1216, ICON = 40;
const KMAX = 1.8, KSTEP = 1.2;   // min zoom is dynamic — see minZoom() (fit-to-height)

const $ = id => document.getElementById(id);

let techs = null, byType = new Map(), parents = new Map();
let loaded = false, built = false;
let contentW = 0, contentH = 0, k = 1;
let eraEntryX = {};        // era key → its entry (min-gridX) column, for the era tabs
const nodeEl = new Map();  // type → card element
const edgeEls = [];        // {el, from, to}
let els = null;            // cached DOM handles, filled on first open
let searchApi = null;      // the header search box (createSearchBox), wired once in initTechTree

// --- data ------------------------------------------------------------------

function prereqList(t, group) {
  const g = t[group];
  if (!g) return [];
  const p = g.PrereqTech;
  if (p == null) return [];
  return Array.isArray(p) ? p : [p];
}
function allPrereqs(t) { return [...prereqList(t, "OrPreReqs"), ...prereqList(t, "AndPreReqs")]; }

async function ensureLoaded() {
  if (loaded) return true;
  const res = await fetch(apiUrl("/api/techs"));
  if (!res.ok) throw new Error(`HTTP ${res.status} for /api/techs`);
  const buf = await res.arrayBuffer();
  // the bytes are gzip; gunzip them in-page — but tolerate a host/CDN that already
  // decompressed via Content-Encoding (then the buffer is plain JSON text)
  let text;
  try {
    const stream = new Response(buf).body.pipeThrough(new DecompressionStream("gzip"));
    text = await new Response(stream).text();
  } catch {
    text = new TextDecoder().decode(buf);
  }
  techs = JSON.parse(text);
  byType = new Map(techs.map(t => [t.Type, t]));
  for (const t of techs) parents.set(t.Type, allPrereqs(t).filter(p => byType.has(p)));
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

  const adv = (t.Advisor || "").replace("ADVISOR_", "");
  const sector = ADV_SECTOR[adv] || "—";
  const pre = allPrereqs(t);
  const d = els.detail;
  d.style.setProperty("--adv", advOf(t));
  d.hidden = false;
  d.innerHTML = `
    <button class="tech-d-close" data-tech-detail-close aria-label="Close detail">&times;</button>
    <div class="tech-d-era">${ERA_NAME[t.Era] || ""}</div>
    <h2 class="tech-d-name"></h2>
    <div class="tech-d-meta">
      <span class="tech-d-tag">${beakerCost(t)}&nbsp;beakers</span>
      <span class="tech-d-tag">${adv ? adv[0] + adv.slice(1).toLowerCase() : "—"}</span>
      <span class="tech-d-tag">Sector: <b>${sector}</b></span>
    </div>
    ${t.help ? `<div class="tech-d-h">Overview</div><div class="tech-d-help"></div>` : ""}
    ${t.quote ? `<div class="tech-d-quote"></div>` : ""}
    ${pre.length ? `<div class="tech-d-h">Requires</div><div class="tech-d-pre"></div>` : ""}
  `;
  d.querySelector(".tech-d-name").textContent = t.name || t.Type;
  if (t.help) d.querySelector(".tech-d-help").textContent = cleanText(t.help);
  if (t.quote) d.querySelector(".tech-d-quote").textContent = cleanText(t.quote);
  const preBox = d.querySelector(".tech-d-pre");
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
  d.querySelector("[data-tech-detail-close]").addEventListener("click", () => { d.hidden = true; });
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

async function open() {
  if (S.techOpen) return;
  cacheEls();
  els.modal.hidden = false;      // reveal the shell (also makes viewport measurable)
  S.techOpen = true;
  pausePlayback();               // modals always run in paused mode
  try {
    await ensureLoaded();
    build();
    buildEraTabs();
    k = Math.max(k, minZoom());   // never open zoomed out past the fit-to-height floor
    applyZoom();
    syncEraTab();
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
  els.detail.hidden = true;
  searchApi?.reset();
  clearHighlight();
  draw();   // repaint the map that was frozen behind the modal
}

function cacheEls() {
  if (els) return;
  els = {
    modal: $("techModal"), viewport: $("techViewport"), sizer: $("techSizer"),
    canvas: $("techCanvas"), edges: $("techEdges"), detail: $("techDetail"), eras: $("techEras"),
    _k: 1,
  };
}

/** Open the tree if closed, close it if open — the F7 shortcut (see shortcuts.mjs). */
export function toggleTech() { S.techOpen ? close() : open(); }

/** Open the tech view idempotently — the Technology advisor's entry point (advisors.mjs). */
export function openTech() { if (!S.techOpen) open(); }

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

export function initTechTree() {
  // the standalone tech button was retired (the Technology advisor is the entry point); wire it
  // only if present. The rest of the init must still run so the Technology advisor works.
  const btn = $("techBtn");
  if (btn) btn.addEventListener("click", open);
  // F7 (toggle) and Escape (close) are dispatched centrally by js/shortcuts.mjs, which
  // calls toggleTech() / closeTech() below.
  // Esc / click outside / the ✕ all return to the map
  $("techModal").addEventListener("click", e => {
    if (e.target.closest("[data-tech-close]")) close();
  });
  $("techZoomIn").addEventListener("click", () => zoomBy(KSTEP));
  $("techZoomOut").addEventListener("click", () => zoomBy(1 / KSTEP));

  // header search: match techs by name (or Type), and on pick select + centre the node. The
  // dropdown/keyboard/clear behaviour is the shared searchbox widget (see searchbox.mjs). The
  // search/row/pick logic is exported (techMatches/techRowHtml/pickTech) so the unified top-bar box
  // can delegate to it in the Technology advisor (§5).
  searchApi = createSearchBox({
    input: $("techSearch"), results: $("techSearchResults"), clear: $("techSearchClear"),
    search: techMatches, renderRow: techRowHtml, onPick: pickTech,
  });
  const vp = $("techViewport");
  vp.addEventListener("scroll", syncEraTab, { passive: true });

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
