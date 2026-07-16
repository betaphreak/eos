"use strict";
// The shared BUTTON tooltip: the little label that appears above any [data-tip] control on the
// stage after a short hover. Nothing to do with the MAP tooltip (maptip.mjs), which follows the
// cursor and reads the world — this one is chrome describing a button, so the two never shared
// anything but a name.
//
// Split out of panel.mjs (which contains no panel — see clock.mjs's header). Self-contained: it
// owns #btntip and wires every [data-tip] under the stage at module eval, so importing it is
// enough. NB that eval-time wiring means a control added to the stage LATER gets no tooltip —
// long-standing behaviour, preserved here rather than quietly changed.
import { stage } from "./core.mjs";

// ---- shared button tooltips (positioned to stay within the stage) ----
const btntip = document.getElementById("btntip");
let tipTimer = 0;
function showBtnTip(el) {
  const text = el.getAttribute("data-tip"); if (!text) return;
  btntip.textContent = text;
  const sr = stage.getBoundingClientRect(), br = el.getBoundingClientRect();
  const bw = btntip.offsetWidth, bh = btntip.offsetHeight;
  let x = br.left - sr.left + br.width / 2 - bw / 2;       // centre on the button, clamp to stage
  x = Math.max(6, Math.min(x, sr.width - bw - 6));
  let y = br.top - sr.top - bh - 8;                        // above by default…
  if (y < 6) y = br.bottom - sr.top + 8;                   // …flip below when there is no room
  btntip.style.left = x + "px"; btntip.style.top = y + "px";
  btntip.classList.add("on");
}
function hideBtnTip() { clearTimeout(tipTimer); btntip.classList.remove("on"); }

// Wire every [data-tip] control under a root. There are TWO roots, and they were two identical
// copies of this loop sitting ~200 lines apart in panel.mjs — one for the map's floating controls
// (the stage), one for the top bar. Same mechanism, same tooltip element; only the root differed.
function wireTips(root) {
  if (!root) return;
  root.querySelectorAll("[data-tip]").forEach(el => {
    el.addEventListener("mouseenter", () => { clearTimeout(tipTimer); tipTimer = setTimeout(() => showBtnTip(el), 320); });
    el.addEventListener("mouseleave", hideBtnTip);
    el.addEventListener("mousedown", hideBtnTip);
  });
}
wireTips(stage);                              // the map's floating controls (zoom, legend, …)
wireTips(document.querySelector(".topbar"));  // the top bar's buttons
