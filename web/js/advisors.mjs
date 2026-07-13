"use strict";
// The Privy Council — a Civ4-style advisor-mode selector layered ABOVE the existing render
// states. Each advisor is a top-bar segment that fills/recolours the canvas and swaps a
// second-row sub-control strip. Choosing one maps onto the low-level S.overlay / S.plane /
// S.techOpen states the LAYERS registry already gates on (layers.mjs) — the render pipeline is
// unchanged; this only groups the controls and drives the existing panel.mjs handlers.
// See docs/privy-council.md.
import { S } from "./core.mjs";
import { setOverlay, setPlane, updateSearchContext } from "./panel.mjs";
import { openTech, closeTech } from "./techtree.mjs";
import { advisorSeat, openAdvisorRail, noteRoster, closeAdvisorRail } from "./advisor-detail.mjs";
import { onLiveRoster } from "./overlays/live.mjs";

// The advisor table — the single extensible source (a future advisor is one more row). `future`
// advisors render as greyed placeholders with their reserved Civ4 F-key; `role` names the court
// seat whose portrait/name labels the advisor (from the live roster, filled in a later step).
export const ADVISORS = [
  { id: "mainmap",    label: "Main Map",           short: "Map",        key: "`"  },
  { id: "domestic",   label: "Domestic Advisor",   short: "Domestic",   key: "F1", future: true },
  { id: "financial",  label: "Financial Advisor",  short: "Financial",  key: "F2", future: true },
  { id: "civics",     label: "Civics Advisor",     short: "Civics",     key: "F3", future: true },
  { id: "foreign",    label: "Foreign Advisor",    short: "Foreign",    key: "F4", role: "foreign" },
  { id: "military",   label: "Military Advisor",   short: "Military",   key: "F5", future: true },
  { id: "technology", label: "Technology Advisor", short: "Technology", key: "F6", role: "technology" },
  { id: "religion",   label: "Religion Advisor",   short: "Religion",   key: "F7", role: "religion" },
  { id: "globe",      label: "Globe View",         short: "Globe",      key: "F11" },
  { id: "zeitgeist",  label: "Zeitgeist",          short: "Zeitgeist",  key: "Z",  live: true },
];
const byId = id => ADVISORS.find(a => a.id === id);

// Foreign remembers its last sub-view (Nation vs Culture) so re-entering restores it.
let foreignSub = "nation";
let selectorEl, subbarEl;

/**
 * Switch to advisor `id`, mapping it onto the render states the LAYERS gates honour. Modelled on
 * panel.mjs setOverlay: it drives the existing handlers (setOverlay / setPlane / openTech), so all
 * their side effects (live SSE connect/disconnect, clock, search context, rail) are reused.
 */
export function setAdvisor(id) {
  const a = byId(id);
  if (!a || a.future) return;
  closeAdvisorRail();     // a prior advisor's character sheet shouldn't linger under the new one
  S.advisor = id;
  // leaving Technology returns the map (paint() resumes); entering it suspends the map behind the tree
  if (id !== "technology" && S.techOpen) closeTech();
  switch (id) {
    case "technology": openTech(); break;
    case "foreign":    setOverlay(foreignSub); break;   // nation | culture
    case "religion":   setOverlay("faith"); break;
    case "zeitgeist":  setOverlay("live"); break;
    case "globe":                                        // Globe = physical map + plane sub-control
    case "mainmap":    setOverlay("none"); break;
  }
  paintSelector();
  showSub(id);
  updateSearchContext();   // §5: swap the top-bar search corpus/placeholder (techs in Technology)
}

// build the advisor selector buttons into #advisorToggle (a .segToggle group). Portraits replace
// the text labels once the portrait bake lands (docs/privy-council.md §3); for now: short label for
// implemented advisors, the F-key for greyed future ones.
function buildSelector() {
  selectorEl.innerHTML = "";
  for (const a of ADVISORS) {
    const b = document.createElement("button");
    b.dataset.advisor = a.id;
    b.textContent = a.future ? a.key : a.short;
    b.setAttribute("aria-pressed", "false");
    const keyHint = a.key === "`" ? "` / Esc" : a.key;
    b.setAttribute("data-tip", a.future
      ? `${a.label} — coming soon (${a.key})`
      : `${a.label} (${keyHint})`);
    if (a.future) b.disabled = true;
    if (a.live) b.classList.add("advisor-live");
    b.addEventListener("click", () => { if (!a.future) setAdvisor(a.id); });
    selectorEl.appendChild(b);
  }
}

function paintSelector() {
  selectorEl.querySelectorAll("button").forEach(b =>
    b.setAttribute("aria-pressed", String(b.dataset.advisor === S.advisor)));
}

// show only the active advisor's sub-control strip; the standalone cost button (top-right) belongs
// to Main Map alone.
function showSub(id) {
  subbarEl.querySelectorAll("[data-sub]").forEach(el => { el.hidden = el.dataset.sub !== id; });
  const costBtn = document.getElementById("costBtn");
  if (costBtn) costBtn.style.display = id === "mainmap" ? "" : "none";
  renderCourt(id);
}

// fill the active role-bearing advisor's court chip with its court member's name (from the live
// roster, §0) — clicking it opens the character sheet in the rail (advisor-detail.mjs §2b). Hidden
// when there's no seated member (no feed / role unfilled). Re-runs on roster change (succession).
function renderCourt(id) {
  const slot = subbarEl.querySelector(`[data-sub="${id}"]`);
  const chip = slot && slot.querySelector(".advisor-court");
  if (!chip) return;
  const seat = advisorSeat(id);
  if (!seat) { chip.hidden = true; return; }
  chip.hidden = false;
  chip.textContent = `${seat.name} ▸`;
  chip.title = `${seat.name} — ${seat.race}. Open the character sheet.`;
  chip.onclick = () => openAdvisorRail(id);
}

// wire the sub-bar's Nation/Culture buttons (Foreign). The Globe plane buttons are the relocated
// #planeToggle, already wired by panel.mjs; setPlane/setOverlay repaint aria-pressed by data-* attr.
function wireSubbar() {
  subbarEl.querySelectorAll('[data-sub="foreign"] [data-ov]').forEach(b =>
    b.addEventListener("click", () => { foreignSub = b.dataset.ov; setOverlay(foreignSub); }));
}

// derive the initial advisor from the render state boot() already applied — no re-apply (avoids a
// second live-feed connect); subsequent user clicks go through setAdvisor.
function deriveAdvisor() {
  if (S.techOpen) return "technology";
  if (S.plane === "underworld") return "globe";
  switch (S.overlay) {
    case "live":   return "zeitgeist";
    case "nation":
    case "culture": foreignSub = S.overlay; return "foreign";
    case "faith":  return "religion";
    default:       return "mainmap";
  }
}

export function initAdvisor() {
  selectorEl = document.getElementById("advisorToggle");
  subbarEl = document.getElementById("advisorSubbar");
  if (!selectorEl || !subbarEl) return;
  buildSelector();
  wireSubbar();
  // the roster arrives on the live feed — re-render the active advisor's court chip on each update
  // (and on succession, when the name/race behind a role changes)
  onLiveRoster(roster => { noteRoster(roster); renderCourt(S.advisor); });
  S.advisor = deriveAdvisor();
  paintSelector();
  showSub(S.advisor);
}
