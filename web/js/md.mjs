// Minimal, dependency-free markdown → HTML for the wiki lore `body` (cleaned markdown from the
// WikiArticleExporter: `## sections`, `**bold**`, `*italic*`, blank-line paragraphs — links are already
// resolved to plain text at export). Escapes HTML FIRST, then applies markup, so the result is safe to
// inject even though the source is trusted lore. web/ has no bundler and no markdown lib by design, so
// this stays tiny; extend it only as the corpus needs.

const esc = (s) => String(s == null ? "" : s)
  .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");

// inline emphasis on already-escaped text (bold before italic so `**` isn't eaten by the italic pass)
const inline = (s) => esc(s)
  .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
  .replace(/\*([^*]+)\*/g, "<em>$1</em>");

/** Render cleaned-markdown lore text to an HTML string (headings → h4, blank-line blocks → paragraphs). */
export function renderMarkdown(md) {
  if (!md) return "";
  return md.split(/\n{2,}/).map((block) => {
    const b = block.trim();
    if (!b) return "";
    const h = b.match(/^(#{1,6})\s+(.*)$/);
    if (h) return `<h4 class="lore-h">${inline(h[2])}</h4>`;
    return `<p>${inline(b.replace(/\n+/g, " "))}</p>`;
  }).join("");
}
