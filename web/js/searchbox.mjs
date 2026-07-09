// A reusable search-with-dropdown widget. Wires an <input>, a results container, and an
// optional clear button to a caller-supplied trio:
//   search(query)            → an array of match objects (already scored/sliced)
//   renderRow(match, i, active) → the row's HTML (MUST carry a data-i="<i>" attribute so a
//                                 click resolves back to its match)
//   onPick(match)            → what to do when a row is chosen
// and owns the behaviour every search box shares: live filtering on input, ↑/↓/Enter/Esc
// keyboard nav, click-to-pick (on mousedown, so it beats the input's blur), the clear button,
// blur-to-close and focus-to-reopen. Used by the province/entity search (top bar) and the
// tech-tree search — the one place this logic lives.
//
// Options: { input, results, clear?, search, renderRow, onPick, renderEmpty?, onEscape? }.
//   renderEmpty(query) → HTML shown when there are no matches (default "No matches."); a caller
//     can return e.g. a "Loading…" line while its data is still arriving.
//   onEscape(e, api)   → custom Escape handling; without it, Escape closes the open dropdown,
//     or blurs the input when the dropdown is already closed.
// Returns an api: { refresh(), reset(), hide(), input, resultsHidden() }.
export function createSearchBox({ input, results, clear, search, renderRow, onPick, renderEmpty, onEscape }) {
  let matches = [], active = -1, lastQ = "";

  function render() {
    if (!matches.length) {
      results.innerHTML = (renderEmpty && renderEmpty(lastQ)) || `<div class="search-empty">No matches.</div>`;
      results.hidden = false;
      return;
    }
    results.innerHTML = matches.map((m, i) => renderRow(m, i, i === active)).join("");
    results.hidden = false;
    results.querySelectorAll("[data-i]").forEach(row =>
      row.addEventListener("mousedown", e => { e.preventDefault(); pick(+row.dataset.i); }));
  }
  function run(raw) {
    lastQ = (raw || "").trim();
    if (clear) clear.hidden = !lastQ;
    if (!lastQ) { matches = []; active = -1; results.hidden = true; return; }
    matches = search(lastQ) || [];
    active = matches.length ? 0 : -1;
    render();
  }
  function pick(i) {
    const m = matches[i];
    if (m == null) return;
    results.hidden = true; input.blur();
    onPick(m);
  }
  function move(d) {
    if (!matches.length) return;
    active = (active + d + matches.length) % matches.length;
    render();
  }

  const api = {
    refresh: () => run(input.value),
    reset: () => { input.value = ""; run(""); },
    hide: () => { results.hidden = true; },
    resultsHidden: () => results.hidden,
    input,
  };

  input.addEventListener("input", () => run(input.value));
  input.addEventListener("focus", () => { if (input.value.trim()) run(input.value); });
  input.addEventListener("blur", () => setTimeout(api.hide, 150));
  input.addEventListener("keydown", e => {
    if (e.key === "Escape") {
      if (onEscape) onEscape(e, api);
      else if (!results.hidden) { e.stopPropagation(); results.hidden = true; }
      else input.blur();
      return;
    }
    if (results.hidden || !matches.length) return;
    if (e.key === "ArrowDown") { e.preventDefault(); move(1); }
    else if (e.key === "ArrowUp") { e.preventDefault(); move(-1); }
    else if (e.key === "Enter") { e.preventDefault(); if (active >= 0) pick(active); }
  });
  if (clear) clear.addEventListener("click", () => { input.value = ""; run(""); input.focus(); });

  return api;
}
