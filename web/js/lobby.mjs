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
// NB this module deliberately does NOT import core.mjs. The lobby opens *during the load* — while
// the bundle is still downloading — and core.mjs reads window.BUNDLE at import time, so importing it
// would mean the lobby could only exist after the very wait it is meant to fill. Its only need from
// core was the server base, which it resolves the same way core does (below).
import { title, status, isOver, canDelete, singlePlayer, ranked, order } from "./lobby-rows.mjs";
// Sign-in lives in the lobby (the account control renders into #siteAuth in its header), so this
// imports auth — which is why auth had to stop importing core.mjs too. Both must survive being
// loaded before the world does.
import { initSiteAuth, whoAmI, onAuthChange } from "./auth.mjs";

const REFRESH_MS = 4000;   // the list is a poll: a browser list does not need frame-accurate pushes
const SLOT_LIMIT = 5;      // mirrors SessionHost.SAVE_SLOT_LIMIT; the server is still the authority

let el, rows = [], signedIn = false, es = null, timer = null, open = false;
let base = "";             // the server this lobby is a lobby FOR

const $ = id => document.getElementById(id);
const apiUrl = path => base + path;

// The same resolution core.mjs does — an explicit ?live=, else the base the bootstrap recorded on
// the fetched bundle, else the default cloud server — but without needing the bundle to exist yet.
function resolveBase(given) {
  if (given) return given.replace(/\/+$/, "");
  const explicit = new URLSearchParams(location.search).get("live");
  if (explicit) return explicit.replace(/\/+$/, "");
  const b = window.BUNDLE;
  return (b && b.live && b.live.base) || "https://dev.civstudio.com";
}

/**
 * Wire the lobby once. Idempotent, and safe to call before the bundle exists.
 * @param serverBase the server this lobby lists (defaults to the one the page is pointed at)
 */
export function initLobby(serverBase) {
  base = resolveBase(serverBase);
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

  // signing in or out repaints the room: which buttons you may press is a fact about you
  onAuthChange(me => { signedIn = !!me.authenticated; paint(); gateChat(); });

  $("lobbySay").onsubmit = e => { e.preventDefault(); say(); };
  $("lobbySolo").onclick = () => showSetup(true);
  $("lobbyRanked").onclick = onRanked;
  $("setupCancel").onclick = () => showSetup(false);
  $("setupRoll").onclick = () => { $("setupSeed").value = rollSeed(); };
  $("setupFound").onclick = found;
}

/**
 * Open the lobby — over the map (from "home"), or over the loading splash while the world is still
 * downloading, which is what it is really for: the wait becomes the choosing.
 *
 * @param serverBase the server to list (defaults to the one the page is pointed at)
 */
export function openLobby(serverBase) {
  initLobby(serverBase);
  open = true;
  el.hidden = false;
  $("lobbyServer").textContent = base.replace(/^https?:\/\//, "");
  showSetup(false);
  // Ask the server who you are, rather than guessing from the rows. Inferring sign-in from "do you
  // own any of these runs" said SIGNED OUT to a signed-in player with no runs yet — who could then
  // never make their first. The account control (sign in / register / sign out) paints itself.
  whoAmI().then(me => { signedIn = !!me.authenticated; paint(); gateChat(); });
  initSiteAuth();
  refresh();
  timer = setInterval(refresh, REFRESH_MS);
  connectChat();
}

// the composer is for people who can post; everyone else gets told how to become one
function gateChat() {
  $("lobbySay").hidden = !signedIn;
  $("lobbySignin").hidden = signedIn;
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
  // a session carries its realm (docs/realms.md §A session carries its realm) — if it lives in another
  // realm, cross to it first, resuming the watch after the reload (the sessionStorage intents survive it).
  const active = new URLSearchParams(location.search).get("realm") || "halcann";
  if (row.realm && row.realm !== active) {
    try { sessionStorage.setItem("cs.spectate", row.id); sessionStorage.setItem("cs.realmSwitch", "1"); } catch { /* private mode */ }
    const u = new URL(location.href);
    u.searchParams.set("realm", row.realm); u.searchParams.delete("p"); u.searchParams.delete("z");
    location.assign(u.toString());
    return;
  }
  closeLobby();
  // The lobby can be open BEFORE the app exists — during the load, which is the point of it — so the
  // choice must survive until live.mjs is there to hear it. Stash it on window (which live.mjs reads
  // when it first connects) AND fire the event, for when the app is already up.
  window.__spectate = row.id;
  const live = document.querySelector('#overlayToggle button[data-ov="live"]');
  if (live && !live.classList.contains("on")) live.click();
  window.dispatchEvent(new CustomEvent("civstudio:spectate", { detail: { id: row.id } }));
}

async function remove(row) {
  if (!window.confirm(`Delete ${title(row)}? This cannot be undone.`)) return;
  let ok = false, error = null;
  try {
    const res = await fetch(apiUrl("/api/sessions/" + encodeURIComponent(row.id)),
      { method: "DELETE", credentials: "include" });
    ok = res.ok;
    if (!ok) { try { error = (await res.json()).error; } catch { /* empty body */ } error = error || ("HTTP " + res.status); }
  } catch (e) { error = String(e && e.message || e); }   // network/CORS failure — don't swallow it
  if (!ok) $("lobbyHint").textContent = error || "could not delete the run";
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
  es = new EventSource(base + "/api/lobby/stream");
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
    // the session lapsed under us — re-ask who we are rather than assume, and let the gate follow
    const me = await whoAmI();
    signedIn = !!me.authenticated;
    gateChat();
    paint();
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
