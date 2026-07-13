"use strict";
// The advisor character sheet (docs/privy-council.md §2b): the court member behind a role-bearing
// advisor (from the live roster, §0) and their household, rendered into the right rail on demand.
// Lazy-fetches the full person record from the server's read-only person endpoint by personId.
import { S, apiUrl } from "./core.mjs";
import { showRail } from "./panel.mjs";
import { liveRoster, liveSid } from "./overlays/live.mjs";
import { diffRoster } from "./roster-diff.mjs";
import { toast } from "./toast.mjs";

const railEl = () => document.getElementById("rail");

// advisor id → the roster role slug it draws its court member from (map-only advisors have none)
const ROLE_OF = { foreign: "foreign", technology: "technology", religion: "religion" };

// the last roster seen, for succession detection across snapshots
let lastRoster = [];

/**
 * Note a fresh roster: toast any advisor succession (a seated role changing holder — the noble died
 * and the selection rule auto-picked a successor, §0). First fills are silent. The server already
 * logs the death to the event feed; this is the transient client cue.
 */
export function noteRoster(roster) {
  const changes = diffRoster(lastRoster, roster);
  lastRoster = roster || [];
  for (const c of changes)
    toast(`<span class="toast-ic">⚑</span><span><b>${esc(cap(c.role))} Advisor</b><br>` +
      `${esc(c.from.name)} has died — succeeded by ${esc(c.to.name)}` +
      `${c.to.race ? ` (${esc(cap(c.to.race))})` : ""}.</span>`);
}

/** Collapse the rail if it currently shows an advisor character sheet and no province is selected —
 *  called on advisor switch so a stale court member's sheet doesn't linger under a new advisor. */
export function closeAdvisorRail() {
  const r = railEl();
  if (r && r.querySelector(".advisor-sheet") && !S.selectedProv) showRail(false);
}

/** The roster entry (court member) backing advisor `id`, or null when the roster lacks it / no feed. */
export function advisorSeat(id) {
  const role = ROLE_OF[id];
  if (!role) return null;
  return liveRoster().find(a => a.role === role) || null;
}

/**
 * Open the advisor's court-member character sheet in the right rail, lazy-fetched by personId from
 * GET /api/sessions/{sid}/person/{id}. Falls back to the roster stub if the fetch fails.
 */
export async function openAdvisorRail(id) {
  const seat = advisorSeat(id);
  const sid = liveSid();
  if (!seat || !sid) return;
  showRail(true);
  railEl().innerHTML = `<div class="detail advisor-sheet"><div class="adv-dim">Loading ${esc(seat.name)}…</div></div>`;
  let detail = null;
  try {
    const r = await fetch(apiUrl(`/api/sessions/${sid}/person/${seat.personId}`), { cache: "no-store" });
    if (r.ok) detail = await r.json();
  } catch { /* fall back to the roster stub below */ }
  if (S.advisor !== id) return;   // the user switched advisors while the fetch was in flight
  railEl().innerHTML = detail ? sheetHtml(seat, detail) : stubHtml(seat);
}

function sheetHtml(seat, d) {
  const skills = (d.skills || []).slice().sort((a, b) => b.level - a.level);
  const top = ROLE_SKILL[seat.role];   // the skill that earned the seat — highlight it
  const bars = skills.map(s => skillRow(s, s.skill === top)).join("");
  const fam = (d.household || []).map(memberRow).join("");
  return `<div class="detail advisor-sheet">
    <div class="adv-head">
      <div class="adv-portrait" style="--h:${hue(d.race)}">${esc(initials(d.name))}</div>
      <div class="adv-id">
        <div class="adv-name">${esc(d.name)}</div>
        <div class="adv-role">${esc(cap(seat.role))} Advisor · ${esc(d.role)}</div>
        <div class="adv-sub">${esc(cap(d.race))} · age ${d.ageYears}</div>
      </div>
    </div>
    <div class="adv-sec">Skills</div>
    <div class="adv-skills">${bars}</div>
    <div class="adv-sec">Household</div>
    <div class="adv-house">${fam || '<div class="adv-dim">Lives alone.</div>'}</div>
  </div>`;
}

// a minimal card when the person endpoint is unreachable (still names the court member from the roster)
function stubHtml(seat) {
  return `<div class="detail advisor-sheet">
    <div class="adv-head">
      <div class="adv-portrait" style="--h:${hue(seat.race)}">${esc(initials(seat.name))}</div>
      <div class="adv-id">
        <div class="adv-name">${esc(seat.name)}</div>
        <div class="adv-role">${esc(cap(seat.role))} Advisor</div>
        <div class="adv-sub">${esc(cap(seat.race))}</div>
      </div>
    </div>
    <div class="adv-dim">Full record unavailable.</div>
  </div>`;
}

function skillRow(s, highlight) {
  const pct = Math.round((s.level / 20) * 100);
  const flame = s.passion === "major" ? "!!" : s.passion === "minor" ? "!" : "";
  return `<div class="adv-skill${highlight ? " hot" : ""}">
    <span class="adv-sk-name">${esc(cap(s.skill))}${flame ? ` <span class="adv-flame">${flame}</span>` : ""}</span>
    <span class="adv-sk-bar"><span style="width:${pct}%"></span></span>
    <span class="adv-sk-lvl">${s.level}</span>
  </div>`;
}

function memberRow(m) {
  return `<div class="adv-member${m.alive ? "" : " dead"}">
    <span class="adv-m-name">${esc(m.name)}</span>
    <span class="adv-m-rel">${esc(m.relation)}${m.alive ? "" : " · †"}</span>
    <span class="adv-m-age">${m.ageYears}</span>
  </div>`;
}

// the skill that earns each seat (matches AdvisorRole.matchSkill) — highlighted in the sheet
const ROLE_SKILL = { technology: "intellectual", foreign: "social" };

const esc = s => String(s == null ? "" : s).replace(/[&<>"]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
const cap = s => s ? s.charAt(0).toUpperCase() + s.slice(1) : s;
const initials = name => (name || "?").split(/\s+/).map(w => w[0]).join("").slice(0, 2).toUpperCase();
// a stable hue per race slug for the placeholder portrait tint (until the real portrait bake, §3)
function hue(race) { let h = 0; for (const c of String(race || "")) h = (h * 31 + c.charCodeAt(0)) % 360; return h; }
