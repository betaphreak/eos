// node --test web/js/md.test.mjs
import test from "node:test";
import assert from "node:assert/strict";
import { renderMarkdown } from "./md.mjs";

test("heading + paragraph with bold/italic", () => {
  assert.equal(
    renderMarkdown("## History\n\nHello **world** and *lore*."),
    '<h4 class="lore-h">History</h4><p>Hello <strong>world</strong> and <em>lore</em>.</p>',
  );
});

test("escapes HTML before markup (no injection)", () => {
  assert.equal(renderMarkdown("a < b & <script>"), "<p>a &lt; b &amp; &lt;script&gt;</p>");
});

test("collapses single newlines inside a paragraph", () => {
  assert.equal(renderMarkdown("line one\nline two"), "<p>line one line two</p>");
});

test("empty / null → empty string", () => {
  assert.equal(renderMarkdown(""), "");
  assert.equal(renderMarkdown(null), "");
  assert.equal(renderMarkdown(undefined), "");
});
