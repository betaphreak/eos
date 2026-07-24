"use strict";
// The CITY SCREEN (docs/city-screen-plan.md) — click a colony's city center and the settlement
// opens: every plot it holds with what stands and what is rising on it, and the crown's build queue
// with the verbs to run it. The Civ4 city screen, in the shape this project already has: a
// full-canvas overlay over #stage (the #techModal pattern — top bar stays live, Esc closes), fed by
// the per-tick snapshot for state and one on-demand fetch for the candidate list.
//
// A colony's construction is not only the crown's queue: the households hammer their own huts on
// their own ground (build-queue B3/B5), and that work shows here plot by plot as it happens.
import { S, P, apiUrl } from "./core.mjs";
import { prettyKey, escHtml } from "./plotlabel.mjs";
import { buildingName, buildingCost } from "./build-catalog.mjs";
import { renderPicker } from "./build-picker.mjs";
import { reorder, append } from "./queue-edit.mjs";
import { buildingsOf } from "./district-plots.mjs";
import { liveSid, liveColony, postCommand } from "./overlays/live.mjs";
import { draw } from "./repaint.mjs";

let detail = null;      // the on-demand sheet: { candidates, canCommand, province, rulerName }
let picker = null;      // the live picker controller while the decree list is open
let pickerOpen = false;

const el = id => document.getElementById(id);

/** Open the settlement screen for the live colony (no-op when there is none). */
export function openCityScreen() {
  const colony = liveColony();
  const modal = el("cityModal");
  if (!colony || !modal) return;
  S.cityOpen = true;
  modal.hidden = false;
  detail = null;
  pickerOpen = false;
  showBare = false;
  render();
  fetchDetail();
  draw();   // the map behind pauses (the tech-tree idiom)
}

/** Close it. */
export function closeCityScreen() {
  const modal = el("cityModal");
  if (modal) modal.hidden = true;
  if (!S.cityOpen) return;
  S.cityOpen = false;
  pickerOpen = false;
  draw();
}

// the candidate list + whether this visitor may command the colony. One fetch per opening: the
// candidates change only as techs land, and canCommand is a property of who is asking, not of when.
async function fetchDetail() {
  const sid = liveSid(), colony = liveColony();
  if (!sid || !colony) return;
  try {
    const r = await fetch(apiUrl(`/api/sessions/${sid}/colony`),
      { cache: "no-store", credentials: "include" });
    if (r.ok) detail = await r.json();
  } catch { /* the screen still reads; only the decree verb needs this */ }
  if (S.cityOpen) render();
}

// ---- rendering -------------------------------------------------------------------------------

function render() {
  const colony = liveColony();
  if (!S.cityOpen || !colony || !el("cityName")) return;
  el("cityName").textContent = colony.name || "Settlement";
  el("citySub").innerHTML = subtitle(colony);
  el("cityPlots").innerHTML = plotsHtml(colony);
  const more = el("cityShowBare");
  if (more) more.onclick = () => { showBare = true; render(); };
  renderQueue(colony);
}

function subtitle(c) {
  const bits = [c.tier ? prettyKey(c.tier) : null,
    `${c.population} household${c.population === 1 ? "" : "s"}`,
    `${c.plotCount} / ${c.maxPlots} plots`];
  if (detail && detail.province) bits.push(escHtml(detail.province));
  if (detail && detail.rulerName) bits.push(escHtml(detail.rulerName) + " rules");
  return bits.filter(Boolean).join(" · ");
}

// the static plot record (place name, terrain, feature) behind a live district, from the plot grid
// the map already loaded for the colony's province. Null before those plots are fetched — the row
// then shows its coordinates alone rather than waiting on them.
function terrainOf(colony, d) {
  const prov = P.find(p => p.id === colony.provinceId);
  if (!prov || !prov._plots) return null;
  return prov._plots.find(q => q.x === d.x && q.y === d.y) || null;
}

// The settlement's ground, in claim order. Plots with nothing on them are folded away by default:
// a mature colony holds dozens of them, and a screen that opens on sixty rows of "worked ground"
// buries the city in its own farmland. The fold SAYS how many it is holding back and opens on a
// click — the plots are still the colony's, and hiding them silently would misreport its size.
function plotsHtml(colony) {
  const districts = colony.districts || [];
  if (!districts.length)
    return `<div class="city-empty">No ground claimed yet.</div>`;
  const rows = [];
  let hidden = 0;
  districts.forEach((d, i) => {
    // a plot with resident households is a hamlet — never fold it away as empty ground, even with
    // nothing built on it yet (city-of-hamlets V1)
    const bare = !buildingsOf(d).length && !(d.underway || []).length && !(d.households > 0);
    if (bare && !showBare) { hidden++; return; }
    rows.push(plotRow(colony, d, i));
  });
  if (hidden)
    rows.push(`<button type="button" class="city-more" id="cityShowBare">${hidden} more plot${
      hidden === 1 ? "" : "s"} of worked ground — show</button>`);
  return rows.join("");
}

let showBare = false;

function plotRow(colony, d, i) {
  const q = terrainOf(colony, d);
  const title = q && q.name ? escHtml(q.name) : `Plot ${i}`;
  const land = q ? [q.terrain && prettyKey(q.terrain), q.feature && prettyKey(q.feature)]
    .filter(Boolean).join(" · ") : `${d.x}, ${d.y}`;
  const center = i === 0 ? `<span class="city-badge">City center</span>` : "";
  const built = buildingsOf(d).map(b =>
    `<span class="city-b own-${b.owner}" title="${escHtml(buildingName(b.id))} · ${houseWho(b)}">${escHtml(houseName(b))}</span>`).join("");
  const rising = (d.underway || []).map(u => {
    const pct = u.cost > 0 ? Math.round(100 * Math.min(1, u.progress / u.cost)) : 0;
    return `<div class="city-rising own-${u.owner}">
      <span class="city-r-name">⚒ ${escHtml(houseName(u))}</span>
      <span class="city-r-bar"><span style="width:${pct}%"></span></span>
      <span class="city-r-pct">${pct}%</span>
      <span class="city-r-who">${houseWho(u)}</span>
    </div>`;
  }).join("");
  // resident peasant households make this plot a hamlet (city-of-hamlets V1). A peopled plot with
  // nothing built yet reads as its hamlet, not "worked ground"; the city center (plot 0) is the
  // civic core, never a hamlet.
  const folk = d.households || 0;
  const isHamlet = folk > 0 && i !== 0;
  const bare = !built && !rising
    ? (isHamlet
        ? `<div class="city-bare">a hamlet · ${folk} household${folk === 1 ? "" : "s"}${
            d.fiefLord ? ` under the ${escHtml(d.fiefLord)} house` : " · crown demesne"}</div>`
        : `<div class="city-bare">worked ground</div>`)
    : "";
  // the plot's fief-lord (the noble/ruler who holds it) — its residents are that lord's vassals
  const fief = d.fiefLord
    ? `<span class="city-fief" title="held in fief by the ${escHtml(d.fiefLord)} house">⚜ ${escHtml(d.fiefLord)}</span>`
    : "";
  // the hamlet's size in households (the "N households" of name · leader · N households)
  const folkChip = folk > 0
    ? `<span class="city-badge" title="${folk} peasant household${folk === 1 ? "" : "s"} live here">${folk}⌂</span>`
    : "";
  return `<div class="city-plot">
    <div class="city-p-head"><span class="city-p-name">${title}</span>${center}${fief}${folkChip}
      <span class="city-p-land">${escHtml(land)}</span></div>
    ${built ? `<div class="city-blds">${built}</div>` : ""}
    ${rising}${bare}
  </div>`;
}

const ownerWord = o => ({ RULER: "the crown", NOBLE: "a noble house", HOUSEHOLD: "a household" })[o]
  || "unowned";

// housing is named for the family that raised it: the chip reads "the <surname> House"; the rung
// itself (e.g. Bark Huts) rides the tooltip / owner line. Any other building keeps its catalog name.
const isHousing = id => typeof id === "string" && id.startsWith("BUILDING_HOUSING_");
// a house is named for the family that raised it AND the rung it is: "Aldresult Palace",
// "Giurovici Bark Huts". Any other building keeps its plain catalog name.
// houseName is raw (the call site escapes it); houseWho returns display-ready HTML (call site does not)
const houseName = b => b.ownerName && isHousing(b.id)
  ? `${b.ownerName} ${buildingName(b.id)}` : buildingName(b.id);
const houseWho = b => b.ownerName && isHousing(b.id)
  ? `the ${escHtml(b.ownerName)} household` : ownerWord(b.owner);

function renderQueue(colony) {
  const q = colony.queue || {};
  const active = el("cityActive");
  if (q.active) {
    const done = q.cost > 0 ? Math.min(1, (q.cost - q.remaining) / q.cost) : 0;
    active.innerHTML = `<div class="city-active">
      <div class="city-a-name">${escHtml(buildingName(q.active))}</div>
      <span class="city-r-bar"><span style="width:${Math.round(done * 100)}%"></span></span>
      <div class="city-a-meta">${Math.ceil(q.remaining)}⚒ remaining${etaText(q)}</div>
    </div>`;
  } else {
    active.innerHTML = `<div class="city-empty">${q.awaiting
      ? "The crown awaits your decree." : "Nothing under construction."}</div>`;
  }

  const pending = q.pending || [];
  el("cityQueue").innerHTML = pending.length
    ? pending.map((id, i) => queueRow(id, i, pending.length)).join("")
    : `<div class="city-empty">Nothing ordered.</div>`;

  // reordering and cancelling need the right to command; DECREEING also needs something to build —
  // an empty candidate list is a real state (nothing unlocked that the centre lacks), and an empty
  // menu reads as a broken button
  const may = !!(detail && detail.canCommand);
  el("cityDecree").hidden = !may || pickerOpen || !(detail.candidates || []).length;
  el("cityPicker").hidden = !pickerOpen;
  el("cityQueue").classList.toggle("readonly", !may);
  if (may) wireQueueButtons(colony, pending);
}

// Time to completion at the colony's trailing donation rate — the reason that rate is projected at
// all. Silent when nothing is flowing ("∞ days" is noise; an absent ETA is honest), and stated in
// the unit a player can hold: a young colony donating a hammer a week is genuinely decades from its
// first monument, and "about 4,462 days" says that far worse than "about 12 years" does.
function etaText(q) {
  if (!(q.ratePerDay > 0) || !(q.remaining > 0)) return "";
  const days = Math.ceil(q.remaining / q.ratePerDay);
  if (days <= 90) return ` · about ${days} day${days === 1 ? "" : "s"}`;
  const years = days / 365;
  if (years < 100) return ` · about ${years < 10 ? years.toFixed(1) : Math.round(years)} years`;
  return " · generations, at this pace";
}

function queueRow(id, i, n) {
  const cost = buildingCost(id);
  return `<div class="city-q" data-i="${i}">
    <span class="city-q-ord">${i + 1}</span>
    <span class="city-q-name">${escHtml(buildingName(id))}</span>
    ${cost ? `<span class="city-q-cost">${cost}⚒</span>` : ""}
    <span class="city-q-ctl">
      <button data-act="up" data-i="${i}" ${i === 0 ? "disabled" : ""} title="Sooner">▲</button>
      <button data-act="down" data-i="${i}" ${i === n - 1 ? "disabled" : ""} title="Later">▼</button>
      <button data-act="drop" data-i="${i}" title="Cancel">✕</button>
    </span>
  </div>`;
}

// ---- the write side --------------------------------------------------------------------------

function wireQueueButtons(colony, pending) {
  for (const b of el("cityQueue").querySelectorAll("button[data-act]"))
    b.onclick = () => submitQueue(colony, reorder(pending, b.dataset.act, +b.dataset.i));
}

// clear-then-append: the command carries both legs, so one submission replaces the queue whole
async function submitQueue(colony, items) {
  const res = await postCommand({ type: "queueBuild", colony: colony.name, items, clear: true });
  if (!res && window.__status)
    window.__status("The crown would not take that order.", "warn");
}

// A mature colony can start hundreds of different buildings (the demo offers ~800), and rendering
// every row as a sprite-bearing button is both slow and unreadable. So the menu opens on the brain's
// best PICK_HEAD and SAYS what it is holding back — a silently truncated list would read as "this
// is all you may build", which is a lie the player would act on.
const PICK_HEAD = 40;
let shownAll = false;

function openPicker() {
  const all = (detail && detail.candidates) || [];
  const shown = shownAll ? all : all.slice(0, PICK_HEAD);
  const add = el("cityPickAdd");
  picker = renderPicker(el("cityPickList"), shown,
    { onChange: p => { add.disabled = !p.length; } });
  add.disabled = true;
  if (!shownAll && all.length > shown.length) {
    const more = document.createElement("button");
    more.type = "button";
    more.className = "bc-item";
    more.innerHTML = `<span class="bc-name">Show all ${all.length} buildings…</span>`;
    more.onclick = () => { shownAll = true; openPicker(); };
    el("cityPickList").appendChild(more);
  }
}

// ---- wiring ----------------------------------------------------------------------------------

window.addEventListener("civstudio:snapshot", () => { if (S.cityOpen) render(); });

// the same window handle the lobby and the server picker expose (window.__lobby / __picker): a
// named door for anything outside the module graph — the headless checks in tools/webverify open
// the screen through it rather than guessing where on the canvas the city centre landed.
window.__city = { open: openCityScreen, close: closeCityScreen };

{
  const close = el("cityClose");
  if (close) close.onclick = closeCityScreen;
  const decree = el("cityDecree");
  if (decree) decree.onclick = () => {
    pickerOpen = true;
    shownAll = false;
    openPicker();
    renderQueue(liveColony());
  };
  const add = el("cityPickAdd");
  if (add) add.onclick = () => {
    const colony = liveColony();
    const items = append((colony.queue && colony.queue.pending) || [],
      picker ? picker.picked() : []);
    pickerOpen = false;
    submitQueue(colony, items);
    renderQueue(colony);
  };
  const cancel = el("cityPickCancel");
  if (cancel) cancel.onclick = () => { pickerOpen = false; renderQueue(liveColony()); };
}
