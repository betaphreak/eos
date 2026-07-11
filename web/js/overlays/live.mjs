"use strict";
// Live overlay — the real WorldMap driven by the running server's SSE feed (see
// docs/client-server.md). Where the Caravan overlay replays a recorded run from data.js,
// this subscribes to the live spectator server (a Container App) and renders the current
// session over the real terrain: the colony, the marching caravans (with trails), and a
// compact HUD that also carries the taxation command (the Phase-B player action). It is a
// pure consumer + command client — it never touches engine internals. The map projection
// (px/py) already pins anything with a lon/lat onto the terrain, so the feed's colonies and
// caravans place with no new geometry.
import { ctx, S, px, py, cssVar, cam, VIEW, baseXr, baseYr, sxSrc, sySrc, BUNDLE, LABEL_FONT } from "../core.mjs";
import { showLiveLog, ingestLog, ingestChat, resetLog, setChatSender } from "../livelog.mjs";

// where the feed lives: the build can inject BUNDLE.live.base; a ?live=<url> query overrides
// it for local testing; otherwise the deployed server.
const LIVE_BASE = new URLSearchParams(location.search).get("live")
  || (BUNDLE.live && BUNDLE.live.base) || "https://dev.civstudio.com";

const PALETTE = ["#e8c37a","#6bd08a","#7aa2e0","#e07a9e","#9e7ae0","#e0a97a","#7ae0d0","#c0e07a"];

let es = null;          // the EventSource, while connected
let sid = null;         // the live session id
let snap = null;        // the latest snapshot
let redraw = () => {};  // main.draw, injected on start
let onState = () => {}; // panel transport-sync callback (play icon + speed chevrons)
const trails = {};      // caravan leader -> [[lat,lon]…] recent path
let framed = false;     // camera centred on the colony once, on the first snapshot

// wall-clock ms per tick for each speed level (1…5): 1 day/s → uncapped
export const LIVE_RATES = [1000, 1000, 500, 250, 100, 0];

// the short label for the connected server, used as the log header prefix (dev.civstudio.com →
// "dev", localhost → "local"); the picked/overridden server determines it.
function serverLabel() {
  try {
    const h = new URL(LIVE_BASE).hostname;
    if (h === "localhost" || h === "127.0.0.1") return "local";
    return h.split(".")[0] || "live";
  } catch { return "live"; }
}

/** Whether the live feed is currently connected (Live mode is active). */
export function liveActive() { return es !== null; }

/** The live session's control state (RUNNING/PAUSED/STOPPED), or null when not connected. */
export function liveState() { return snap ? snap.state : null; }

/**
 * Connect to the live feed and start driving the overlay.
 * @param onRedraw main's draw()
 * @param onSessionState called with the session state on each snapshot (transport sync)
 */
export async function startLive(onRedraw, onSessionState) {
  redraw = onRedraw || redraw;
  onState = onSessionState || onState;
  framed = false;
  hud(true);
  setHudStatus("connecting…");
  try {
    const list = await (await fetch(LIVE_BASE + "/api/sessions")).json();
    if (!list.length) { setHudStatus("no live session"); return; }
    sid = list[0].id;
    es = new EventSource(LIVE_BASE + "/api/sessions/" + sid + "/stream");
    es.onmessage = e => {
      try { onSnapshot(JSON.parse(e.data)); } catch (err) { /* ignore a bad frame */ }
    };
    // lobby chat rides its own SSE event (immediate, not tick-paced); feed it to the log bar
    es.addEventListener("chat", e => {
      try { ingestChat(JSON.parse(e.data)); } catch (err) { /* ignore a bad chat frame */ }
    });
    setChatSender(postChat);
    // EventSource retries transient drops on its own (readyState CONNECTING) — just reflect that in
    // the HUD. Only a permanently CLOSED feed (readyState 2: fatal, gave up) is a real loss → drop
    // the user to server selection so they can reconnect (via window.__picker, from index.html).
    es.onerror = () => {
      setHudStatus("reconnecting…");
      if (es && es.readyState === 2 && window.__picker && window.__picker.lost)
        window.__picker.lost("Lost connection to the live server");
    };
  } catch (err) {
    setHudStatus("offline — " + LIVE_BASE);
  }
}

/** Send a control action (pause/resume/step/rate) to the live session. */
export async function controlLive(action, value) {
  if (!sid) return;
  const body = value === undefined ? { action } : { action, value };
  try {
    // credentials: the owner check reads the session cookie; a write to an owned session needs it
    // (cross-origin to LIVE_BASE, so the default same-origin credentials mode would drop the cookie)
    await fetch(LIVE_BASE + "/api/sessions/" + sid + "/control",
      { method: "POST", credentials: "include",
        headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
  } catch (err) { /* the next snapshot will reflect the true state anyway */ }
}

/** Disconnect and tear down the overlay (leaving Live mode). */
export function stopLive() {
  if (es) { es.close(); es = null; }
  snap = null; sid = null;
  for (const k in trails) delete trails[k];
  resetLog();
  liveTabRunning(false);
  hud(false);
}

// tint the Spectate tab's background while the live session is RUNNING (a "live / on air" cue);
// cleared when paused, stopped, or on disconnect.
function liveTabRunning(on) {
  const btn = document.querySelector('#overlayToggle button[data-ov="live"]');
  if (btn) btn.classList.toggle("live-running", on);
}

function onSnapshot(s) {
  snap = s;
  s.caravans.forEach(c => {
    const t = (trails[c.leader] = trails[c.leader] || []);
    t.push([c.latitude, c.longitude]);
    if (t.length > 500) t.shift();
  });
  if (!framed && s.colonies[0]) { frameOn(s.colonies[0].latitude, s.colonies[0].longitude, 6); framed = true; }
  renderHud();
  ingestLog(s.log);           // feed the event-log bar this frame's new lines
  onState(s.state, s.date);   // sync the transport controls (play icon, speed, date) to the server
  liveTabRunning(s.state === "RUNNING"); // tint the Spectate tab while the session is live/unpaused
  redraw();
}

// centre the camera on a lon/lat at scale k (used once, so Live mode opens on the action
// rather than the whole world). Mirrors the camera math in main/panel (px = cam.x +
// cam.k*baseXr(sxSrc(lon))), solved for cam.x/cam.y so the point lands at screen centre.
function frameOn(lat, lon, k) {
  if (!(VIEW.w > 0)) return;
  cam.k = k;
  cam.x = VIEW.w / 2 - cam.k * baseXr(sxSrc(lon));
  cam.y = VIEW.h / 2 - cam.k * baseYr(sySrc(lat));
  S.baseVersion++;
}

/** Draw the live colony + caravans. Called by main.renderScene per world-copy in Live mode. */
export function drawLive() {
  if (!snap) return;

  // caravan trails, then dots
  snap.caravans.forEach((c, i) => {
    const col = PALETTE[i % PALETTE.length];
    const tr = trails[c.leader] || [];
    if (tr.length > 1) {
      ctx.beginPath();
      tr.forEach((p, k) => { const x = px(p[1]), y = py(p[0]); k ? ctx.lineTo(x, y) : ctx.moveTo(x, y); });
      ctx.lineJoin = "round"; ctx.lineCap = "round";
      // a dark casing under the route + a soft glow, so it reads over any terrain rather than
      // blending in; then the bright coloured line on top
      ctx.shadowColor = "rgba(3,6,11,.9)"; ctx.shadowBlur = 4;
      ctx.strokeStyle = "rgba(6,9,14,.9)"; ctx.lineWidth = 5.5; ctx.stroke();
      ctx.shadowBlur = 0;
      ctx.strokeStyle = col; ctx.globalAlpha = .95; ctx.lineWidth = 2.6; ctx.stroke();
      ctx.globalAlpha = 1;
    }
    const x = px(c.longitude), y = py(c.latitude), r = c.settled ? 6 : 4.6;
    ctx.beginPath(); ctx.arc(x, y, r, 0, 7); ctx.fillStyle = col; ctx.fill();
    ctx.beginPath(); ctx.arc(x, y, r, 0, 7); ctx.strokeStyle = "#0b0f16"; ctx.lineWidth = 1.6; ctx.stroke();
  });

  // the colony: a settlement marker + name
  const colony = snap.colonies[0];
  if (colony && Number.isFinite(colony.latitude)) {
    const x = px(colony.longitude), y = py(colony.latitude);
    ctx.fillStyle = cssVar("--accent") || "#e8b76a";
    ctx.strokeStyle = "#0b0f16"; ctx.lineWidth = 2;
    ctx.beginPath(); ctx.rect(x - 5, y - 5, 10, 10); ctx.fill(); ctx.stroke();
    ctx.fillStyle = "#eef2f8"; ctx.font = `600 13px ${LABEL_FONT}`;
    ctx.fillText(colony.name || "colony", x + 9, y + 4);
  }
}

// ---- the live HUD (a floating panel over the map, only in Live mode) ----
const el = id => document.getElementById(id);
let hudWired = false;

function hud(show) {
  const box = el("liveHud");
  if (box) {
    box.hidden = !show;
    // clicks/scroll on the HUD (incl. the tax inputs) must not fall through to pan/zoom/pick the map
    if (show && !box.__mapGuard) {
      ["pointerdown", "mousedown", "click", "touchstart", "wheel"].forEach(t =>
        box.addEventListener(t, e => e.stopPropagation(), { passive: true }));
      box.__mapGuard = true;
    }
  }
  showLiveLog(show, serverLabel());   // the event-log bar tracks Live mode
}

function setHudStatus(text) {
  const s = el("liveState");
  if (s) { s.textContent = text; s.className = "badge"; }
}

function renderHud() {
  if (!snap) return;
  const c = snap.colonies[0] || {};
  el("liveSid").textContent = snap.sessionId || "";
  el("liveTick").textContent = snap.tick;
  el("liveDate").textContent = snap.date || "";
  const st = el("liveState"); st.textContent = snap.state; st.className = "badge " + snap.state;
  el("liveStats").innerHTML = c.name ? `
    <tr><td>${c.name}</td><td>${c.population} 👤</td></tr>
    <tr><td>pool / children</td><td>${c.poolSize} / ${c.children}</td></tr>
    <tr><td>firms · nobles</td><td>${c.firms} · ${c.nobles}</td></tr>
    <tr><td>food price</td><td>${(c.necessityPrice||0).toFixed(2)}</td></tr>
    <tr><td>caravans</td><td>${snap.caravans.length}</td></tr>` : "";
  // current tax levers; prefill the inputs once
  el("liveBankTaxCur").textContent = (c.bankProfitTax||0).toFixed(3);
  el("liveNobleTaxCur").textContent = (c.nobleIncomeTax||0).toFixed(3);
  if (!hudWired && c.name) {
    el("liveBankTax").value = (c.bankProfitTax||0).toFixed(2);
    el("liveNobleTax").value = (c.nobleIncomeTax||0).toFixed(2);
    el("liveApplyTax").onclick = applyTax;
    hudWired = true;
  }
}

// post a lobby chat message to the session (server attaches the authoritative username). The owner
// check doesn't apply — any signed-in user may chat — but the cookie must ride (cross-origin).
async function postChat(text) {
  if (!sid) return;
  try {
    await fetch(LIVE_BASE + "/api/sessions/" + sid + "/chat",
      { method: "POST", credentials: "include",
        headers: { "Content-Type": "application/json" }, body: JSON.stringify({ text }) });
  } catch (err) { /* transient — the message just won't post */ }
}

async function postCommand(body) {
  if (!sid) return null;
  // credentials: player commands are owner-gated (see docs/authentication.md) — send the cookie
  const r = await fetch(LIVE_BASE + "/api/sessions/" + sid + "/commands",
    { method: "POST", credentials: "include",
      headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
  return r.ok ? r.json() : null;
}

async function applyTax() {
  const msg = el("liveTaxMsg");
  const bank = parseFloat(el("liveBankTax").value), noble = parseFloat(el("liveNobleTax").value);
  const acks = [];
  if (Number.isFinite(bank))  acks.push(await postCommand({ type: "setTaxRate", lever: "bankProfit",  rate: bank }));
  if (Number.isFinite(noble)) acks.push(await postCommand({ type: "setTaxRate", lever: "nobleIncome", rate: noble }));
  const ticks = acks.filter(a => a && a.tick != null).map(a => a.tick);
  msg.textContent = ticks.length ? "applied at tick " + Math.max(...ticks) : "nothing to apply";
}
