"use strict";
// The caravan composition panel (docs/caravan.md): click a band's map icon and its makeup fills the
// right rail — a band-average skill profile and the roster, ordered by SURVIVAL descending (the
// leader-succession order), the leader flagged. Lazy-fetches GET /api/sessions/{sid}/caravan/{id}
// (CaravanController); re-resolves the selected band by id each snapshot to keep the header live and
// refetch when the makeup changes, and deselects when the band settles or dissolves. Reuses the
// advisor sheet's markup/classes (adv-*), written straight into the rail like openAdvisorRail.
import { S, apiUrl } from "./core.mjs";
import { showRail } from "./rail.mjs";
import { liveSid, liveCaravans } from "./overlays/live.mjs";

const railEl = () => document.getElementById("rail");

let selId = null;        // the selected band's id, or null
let lastDetail = null;   // the last composition fetched, kept across live header refreshes
let lastFetchKey = "";   // (bandSize|leader) of the last roster fetch — refetch when it changes

/** Open (or toggle off) a band's composition panel from its {@code CaravanView} `c`. */
export function openCaravanRail(c) {
  if (selId === c.id) { closeCaravanRail(); return; }   // a re-click closes it
  selId = c.id;
  S.selectedProv = null;   // a band and a province are mutually exclusive rail subjects
  lastDetail = null;
  lastFetchKey = "";
  showRail(true);
  render(c);               // header immediately; the roster fills in after the fetch
  fetchAndRender(c);
}

/** Collapse the panel (an explicit deselect, or the band is gone). */
export function closeCaravanRail() {
  selId = null;
  lastDetail = null;
  if (railEl() && railEl().querySelector(".caravan-sheet")) showRail(false);
}

// each snapshot: keep the selected band's header live, refetch its roster when the makeup changed,
// and drop the selection when the band is gone (settled/dissolved) or a province took over the rail.
function onSnapshot() {
  if (selId == null) return;
  if (!railEl() || !railEl().querySelector(".caravan-sheet")) { selId = null; lastDetail = null; return; }
  const c = liveCaravans().find(x => x.id === selId);
  if (!c) { render(null); return; }   // the band has settled or dissolved
  render(c);                          // live header (position / size / larder); roster stays put
  fetchAndRender(c);                  // refetch only if the makeup key changed (guarded within)
}

async function fetchAndRender(c) {
  const sid = liveSid();
  if (!sid) return;
  const key = c.bandSize + "|" + c.leader;
  if (key === lastFetchKey && lastDetail) return;   // unchanged makeup — the roster we have still stands
  lastFetchKey = key;
  const id = c.id;
  let detail = null;
  try {
    const r = await fetch(apiUrl(`/api/sessions/${sid}/caravan/${id}`), { cache: "no-store" });
    if (r.ok) detail = await r.json();
  } catch { /* keep the header-only view */ }
  if (selId !== id) return;   // the selection changed while the fetch was in flight
  lastDetail = detail;
  render(c);
}

// paint the rail: the band's header + stats + skill profile + roster (or a "dispersed" note when the
// band is gone). Uses whatever composition has been fetched so far (lastDetail).
function render(c) {
  const el = railEl();
  if (!el) return;
  el.innerHTML = c ? sheetHtml(c, lastDetail) : dispersedHtml();
  const back = el.querySelector("#backCaravan");
  if (back) back.onclick = closeCaravanRail;
}

function sheetHtml(c, d) {
  const size = d ? d.bandSize : c.bandSize;
  const skills = d
    ? d.skills.slice().sort((a, b) => b.avg - a.avg).map(skillBar).join("")
    : `<div class="adv-dim">Reading the muster…</div>`;
  const roster = d ? d.members.map(memberRow).join("") : "";
  return `<div class="detail caravan-sheet">
    <button class="backbtn" id="backCaravan">← Map</button>
    <div class="adv-head">
      <div class="adv-portrait" style="--h:${roleHue(c.role)}">${roleGlyph(c.role)}</div>
      <div class="adv-id">
        <div class="adv-name">${esc(c.unitName || cap(c.role) || "Band")}</div>
        <div class="adv-role">${esc(cap(c.role || "Caravan"))} · Band of ${size}</div>
        <div class="adv-sub">${esc(c.leader)} leads · ${esc(c.province)}</div>
      </div>
    </div>
    <div class="statrow">
      <div class="stat"><b>${Math.round(c.larder)}</b><span>Larder</span></div>
      <div class="stat"><b>${Math.round(c.hoard)}</b><span>Hoard cu</span></div>
    </div>
    <div class="adv-sec">Skill profile <span class="adv-dim">band avg</span></div>
    <div class="adv-skills">${skills}</div>
    ${d ? `<div class="adv-sec">Roster <span class="adv-dim">by survival</span></div>
    <div class="adv-house">${roster}</div>` : ""}
  </div>`;
}

function dispersedHtml() {
  return `<div class="detail caravan-sheet">
    <button class="backbtn" id="backCaravan">← Map</button>
    <div class="adv-head"><div class="adv-id">
      <div class="adv-name">Band dispersed</div>
      <div class="adv-sub">This caravan has settled or dissolved.</div>
    </div></div>
  </div>`;
}

// one aggregate skill bar (avg level 0..20 → a percentage bar, the average shown to a decimal)
function skillBar(s) {
  const pct = Math.max(0, Math.min(100, Math.round((s.avg / 20) * 100)));
  return `<div class="adv-skill">
    <span class="adv-sk-name">${esc(cap(s.skill))}</span>
    <span class="adv-sk-bar"><span style="width:${pct}%"></span></span>
    <span class="adv-sk-lvl">${s.avg.toFixed(1)}</span>
  </div>`;
}

// one roster row (survival-descending): the leader badged, race · age, and the SURVIVAL level that
// orders the roster in the level slot
function memberRow(m) {
  return `<div class="adv-member">
    <span class="adv-m-name">${m.leader ? '<span class="cv-lead">★</span> ' : ""}${esc(m.name)}</span>
    <span class="adv-m-rel">${esc(cap(m.race))} · ${m.age}</span>
    <span class="adv-m-age">S${m.survival}</span>
  </div>`;
}

const ROLE_HUE = { SETTLER: 40, WORKER: 140, EXPLORER: 205, MILITARY: 350 };
const ROLE_GLYPH = { SETTLER: "⌂", WORKER: "⚒", EXPLORER: "◎", MILITARY: "⚔" };
const roleHue = r => ROLE_HUE[r] ?? 205;
const roleGlyph = r => ROLE_GLYPH[r] || "◆";

const esc = s => String(s == null ? "" : s).replace(/[&<>"]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
const cap = s => s ? s.charAt(0).toUpperCase() + s.slice(1).toLowerCase() : s;

window.addEventListener("civstudio:snapshot", onSnapshot);
