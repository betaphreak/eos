"use strict";
// The Spectator Lobby (docs/spectator-lobby.md Phase 5) — the room you are in before you have
// picked a session: a browser of what is running, a chat that belongs to no session, and the two
// ways to play.
//
// Opened over the live map by the brand ("home"), dismissed with Esc or a click outside — the
// gesture the server picker already taught. The picker keeps its own job (choosing a server); the
// lobby is what you get once one is chosen, and links back to it.
//
// Everything here is painting and plumbing. The decisions — what a row is called, what its status
// says, which face a button wears — live in lobby-rows.mjs, pure and unit-tested.
import { apiUrl, SERVER_BASE } from "./core.mjs";
import { title, status, isOver, canDelete, singlePlayer, ranked, order } from "./lobby-rows.mjs";

const REFRESH_MS = 4000;   // the list is a poll: a browser list does not need frame-accurate pushes
const SLOT_LIMIT = 5;      // mirrors SessionHost.SAVE_SLOT_LIMIT; the server is still the authority

let el, rows = [], signedIn = false, es = null, timer = null, open = false;

const $ = id => document.getElementById(id);

/** Wire the lobby once, at boot. Idempotent. */
export function initLobby() {
  el = $("lobby");
  if (!el || el.dataset.wired) return;
  el.dataset.wired = "1";

  $("lobbyClose").onclick = closeLobby;
  el.addEventListener("click", e => { if (e.target === el) closeLobby(); });
  document.addEventListener("keydown", e => {
    if (e.key !== "Escape" || !open) return;
    // the setup panel is a step INSIDE the lobby: Esc backs out of it first, not all the way to the map
    if (!$("lobbySetup").hidden) { e.preventDefault(); showSetup(false); return; }
    e.preventDefault();
    closeLobby();
  });

  $("lobbySay").onsubmit = e => { e.preventDefault(); say(); };
  $("lobbySolo").onclick = () => showSetup(true);
  $("lobbyRanked").onclick = onRanked;
  $("setupCancel").onclick = () => showSetup(false);
  $("setupRoll").onclick = () => { $("setupSeed").value = rollSeed(); };
  $("setupFound").onclick = found;
}

/** Open the lobby over the map. */
export function openLobby() {
  initLobby();
  open = true;
  el.hidden = false;
  $("lobbyServer").textContent = SERVER_BASE.replace(/^https?:\/\//, "");
  showSetup(false);
  refresh();
  timer = setInterval(refresh, REFRESH_MS);
  connectChat();
}

/** Back to the map. */
export function closeLobby() {
  open = false;
  if (el) el.hidden = true;
  if (timer) { clearInterval(timer); timer = null; }
  if (es) { es.close(); es = null; }   // the chat feed is only live while you are in the room
}

/** Whether the lobby is the thing on screen (so Esc/shortcuts know who owns the key). */
export const lobbyOpen = () => open;

// ---- the list -------------------------------------------------------------

async function refresh() {
  try {
    const res = await fetch(apiUrl("/api/sessions"), { credentials: "include", cache: "no-store" });
    if (!res.ok) return;
    rows = await res.json();
    // "mine" is the server's answer to who you are — a cheaper, truer signal than asking separately
    signedIn = rows.some(r => r.mine) || signedIn;
    paint();
  } catch { /* a lobby that cannot reach the server just shows what it last knew */ }
}

function paint() {
  const list = $("lobbySessions");
  list.textContent = "";
  const ordered = order(rows);
  $("lobbyCount").textContent = ordered.length ? `(${ordered.length})` : "";
  if (!ordered.length) {
    const empty = document.createElement("div");
    empty.className = "lb-empty";
    empty.textContent = "Nothing is running here yet.";
    list.append(empty);
  }
  for (const row of ordered) list.append(rowEl(row));

  const solo = singlePlayer(rows, signedIn, SLOT_LIMIT);
  applyBtn($("lobbySolo"), solo);
  applyBtn($("lobbyRanked"), ranked(rows, signedIn));
  $("lobbyHint").textContent = solo.hint;
}

function rowEl(row) {
  const item = document.createElement("div");
  item.className = "lb-item" + (isOver(row) ? " over" : "") + (row.mine ? " mine" : "");
  item.setAttribute("role", "listitem");

  const main = document.createElement("button");
  main.className = "lb-item-main";
  main.onclick = () => spectate(row);
  const h = document.createElement("span");
  h.className = "lb-item-title";
  h.textContent = title(row);
  const s = document.createElement("span");
  s.className = "lb-item-sub";
  s.textContent = status(row);
  main.append(h, s);

  const meta = document.createElement("span");
  meta.className = "lb-item-meta";
  meta.textContent = row.watching ? `👁 ${row.watching}` : "";

  item.append(main, meta);
  if (canDelete(row)) {
    const del = document.createElement("button");
    del.className = "lb-del";
    del.textContent = "Delete";
    del.title = "Delete this run";
    del.onclick = () => remove(row);
    item.append(del);
  }
  return item;
}

function applyBtn(btn, face) {
  btn.textContent = face.label;
  btn.disabled = !face.enabled;
  btn.title = face.hint || "";
  btn.dataset.session = face.id || "";
  btn.dataset.join = face.join ? "1" : "";
}

// Watching a session means two things: be in Live mode, and be pointed at THAT session. The first is
// the overlay toggle's own affordance (click it rather than reach into its state); the second is an
// event live.mjs listens for, so neither module imports the other.
function spectate(row) {
  closeLobby();
  const live = document.querySelector('#overlayToggle button[data-ov="live"]');
  if (live && !live.classList.contains("on")) live.click();
  window.dispatchEvent(new CustomEvent("civstudio:spectate", { detail: { id: row.id } }));
}

async function remove(row) {
  if (!window.confirm(`Delete ${title(row)}? This cannot be undone.`)) return;
  try {
    await fetch(apiUrl("/api/sessions/" + encodeURIComponent(row.id)),
      { method: "DELETE", credentials: "include" });
  } catch { /* the refresh below tells the truth either way */ }
  refresh();
}

// ---- play -----------------------------------------------------------------

function onRanked() {
  const btn = $("lobbyRanked");
  if (!btn.dataset.session) return;
  if (btn.dataset.join) return join(btn.dataset.session);
  spectate({ id: btn.dataset.session });
}

async function join(id) {
  const out = await post("/api/sessions/" + encodeURIComponent(id) + "/join", {});
  if (out.ok) spectate({ id });
  else $("lobbyHint").textContent = out.error || "could not take a seat";
}

function showSetup(on) {
  $("lobbySetup").hidden = !on;
  $("lobbySolo").disabled = on || !singlePlayer(rows, signedIn, SLOT_LIMIT).enabled;
  if (on) {
    $("setupSeed").value = rollSeed();
    $("setupHint").textContent = singlePlayer(rows, signedIn, SLOT_LIMIT).hint;
    $("setupSeed").focus();
  }
}

// a seed the player can read back, share, and re-found the same world from
const rollSeed = () => String(Math.floor(Math.random() * 9_000_000) + 1_000_000);

async function found() {
  const seed = Number($("setupSeed").value);
  const provinceId = Number($("setupProvince").value);
  if (!Number.isFinite(seed) || !Number.isFinite(provinceId)) {
    $("setupHint").textContent = "seed and province are numbers";
    return;
  }
  $("setupFound").disabled = true;
  const out = await post("/api/sessions", { seed, provinceId, scenario: "caravan-demo" });
  $("setupFound").disabled = false;
  if (!out.ok) {
    // the server's refusals are worth reading out loud — a full shelf, or a finished run
    $("setupHint").textContent = out.error || "could not found the run";
    refresh();
    return;
  }
  showSetup(false);
  refresh();
  spectate({ id: out.body.id });   // it lands PAUSED: survey the world, then press play
}

// ---- chat -----------------------------------------------------------------

function connectChat() {
  if (es) es.close();
  $("lobbyChat").textContent = "";
  // No withCredentials: listening is anonymous (the house rule), and asking for credentials turns
  // this into a cross-origin request the server's CORS does not allow — which failed silently, so
  // the chat simply never arrived. The session feed has always connected plainly, for the same reason.
  es = new EventSource(SERVER_BASE + "/api/lobby/stream");
  es.addEventListener("chat", e => {
    try { addLine(JSON.parse(e.data)); } catch { /* ignore a bad frame */ }
  });
  es.onerror = () => { /* the lobby is not worth a reconnect storm; reopening re-subscribes */ };
}

function addLine(msg) {
  const box = $("lobbyChat");
  const line = document.createElement("div");
  line.className = "lb-line";
  const who = document.createElement("b");
  who.textContent = msg.user + ": ";
  line.append(who, document.createTextNode(msg.text));
  box.append(line);
  box.scrollTop = box.scrollHeight;
}

async function say() {
  const input = $("lobbyText");
  const text = input.value.trim();
  if (!text) return;
  input.value = "";
  const out = await post("/api/lobby/chat", { text });
  if (out.status === 401) {
    // signed out: the composer is not a lie you get to type into
    $("lobbySay").hidden = true;
    $("lobbySignin").hidden = false;
  }
}

async function post(path, body) {
  try {
    const res = await fetch(apiUrl(path), {
      method: "POST", credentials: "include",
      headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
    });
    let parsed = null;
    try { parsed = await res.json(); } catch { /* 204s and empties have no body */ }
    return { ok: res.ok, status: res.status, body: parsed || {},
      error: parsed && parsed.error };
  } catch (e) {
    return { ok: false, status: 0, body: {}, error: String(e && e.message || e) };
  }
}
