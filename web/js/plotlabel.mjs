// Pure label helpers for the plot hover tooltip (used by panel.mjs). Kept in their own module so
// they can be unit-tested without a DOM (see plotlabel.test.mjs).

// Title-case a Civ4 type key, dropping the BONUS_/FEATURE_/TERRAIN_ prefix:
//   TERRAIN_GRASSLAND -> "Grassland", FEATURE_FOREST -> "Forest", BONUS_IRON -> "Iron".
export const prettyKey = t =>
  t.replace(/^(BONUS|FEATURE|TERRAIN)_/, "").toLowerCase().replace(/_/g, " ").replace(/\b\w/g, c => c.toUpperCase());

// Escape the three HTML-significant chars, for safely inserting an external (GeoNames) place name
// into innerHTML.
export const escHtml = s =>
  String(s).replace(/[&<>]/g, c => (c === "&" ? "&amp;" : c === "<" ? "&lt;" : "&gt;"));

// Tooltip HTML for the plot under the cursor: the real-world place name (bold), then
// "terrain · feature" on a second line. Any missing part is omitted; returns "" for a bare plot.
export function plotTip(q){
  const lines = [];
  if (q.name) lines.push(`<b>${escHtml(q.name)}</b>`);
  const bits = [q.terrain && prettyKey(q.terrain), q.feature && prettyKey(q.feature)]
    .filter(Boolean).join(" · ");
  if (bits) lines.push(`<span class="r">${bits}</span>`);
  return lines.join("<br>");
}
