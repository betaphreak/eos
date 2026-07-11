"use strict";
// Live event-log bar. In Live mode a collapsed one-line strip sits at the bottom of the map
// showing the latest event; clicking it expands a scrollable, chat-style history above it. It is
// fed by live.mjs from each SSE snapshot's `log` delta (see docs/client-server.md). Each line
// reads "<server>@<in-game-date>  <message>" — the server label is passed in from the connected
// server. "show all" reveals routine churn; otherwise only curated events (foundings, deaths,
// policy changes, anomalies — flagged server-side) are shown.

const el = id => document.getElementById(id);
const history = [];      // {date, text, curated} accrued over this session
let server = "live";     // header prefix (e.g. "dev"), set on show
let expanded = false;
let wired = false;

const MAX = 600;         // cap the client-side history

/** Show/hide the bar (wiring it once). serverLabel becomes the header prefix, e.g. "dev". */
export function showLiveLog(show, serverLabel) {
  if (serverLabel) server = serverLabel;
  const box = el("liveLog");
  if (box) box.hidden = !show;
  if (show) wire();
}

/** Append a snapshot's log delta and refresh the view. */
export function ingestLog(lines) {
  if (!lines || !lines.length) return;
  for (const l of lines) history.push(l);
  while (history.length > MAX) history.shift();
  renderBar();
  if (expanded) renderHistory();
}

/** Clear history and collapse (on disconnect / leaving Live mode). */
export function resetLog() {
  history.length = 0;
  expanded = false;
  const h = el("liveLogHistory"); if (h) h.hidden = true;
  const line = el("liveLogLine"); if (line) line.textContent = "…";
}

function wire() {
  if (wired) return;
  const latest = el("liveLogLatest");
  if (!latest) return;
  const toggle = () => {
    expanded = !expanded;
    const h = el("liveLogHistory");
    if (h) h.hidden = !expanded;
    if (expanded) renderHistory();
  };
  latest.addEventListener("click", toggle);
  latest.addEventListener("keydown", e => {
    if (e.key === "Enter" || e.key === " ") { e.preventDefault(); toggle(); }
  });
  const all = el("liveLogAll");
  if (all) all.addEventListener("change", () => { renderBar(); if (expanded) renderHistory(); });
  wired = true;
}

function showAll() { const a = el("liveLogAll"); return !!(a && a.checked); }
function visible() { return showAll() ? history : history.filter(l => l.curated); }
function header(l) { return `${server}@${l.date || "----"}`; }

function sevClass(l) { return l.sev && l.sev !== "info" ? " sev-" + l.sev : ""; }

function renderBar() {
  const line = el("liveLogLine"); if (!line) return;
  const vis = visible();
  if (!vis.length) { line.textContent = "…"; line.className = "live-log-line"; return; }
  const l = vis[vis.length - 1];
  line.textContent = `${header(l)}  ${l.text}`;
  line.className = "live-log-line" + sevClass(l);
}

function renderHistory() {
  const box = el("liveLogLines"); if (!box) return;
  const vis = visible();
  box.innerHTML = vis.map(l =>
    `<div class="live-log-row${l.curated ? " cur" : ""}${sevClass(l)}">` +
    `<span class="live-log-hdr">${esc(header(l))}</span>${esc(l.text)}</div>`
  ).join("");
  box.scrollTop = box.scrollHeight; // pin to newest
}

function esc(s) {
  return String(s).replace(/[&<>]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;" }[c]));
}
