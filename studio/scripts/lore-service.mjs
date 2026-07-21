// lore-service.mjs — the served lore-chatbot endpoint (P5 C2/C3). A tiny dependency-light HTTP service
// (reuses studio's `pg`) over lore-lib: TEI embeddings + pgvector retrieval, optionally a Claude-grounded
// answer. Deploy as its own Azure Container App alongside TEI (like the seed/backfill workflow). The web
// chat UI (C5) and the P4 lore panels can call it. Run from studio/: node scripts/lore-service.mjs
//
//   GET  /health
//   GET  /api/lore/search?q=…&k=8   → { results:[{title,section,entityRef,entityKey,wikiUrl,text,sim}] }   (no key)
//   POST /api/lore/ask  {question}  → { answer, sources }   (needs ANTHROPIC_API_KEY; 503 without one)
import "dotenv/config";
import { createServer } from "node:http";
import { pg, retrieve, askAgent } from "./lore-lib.mjs";

const PORT = process.env.LORE_PORT || 8090;

const cors = (res) => {
  res.setHeader("access-control-allow-origin", "*"); // lore is public CC BY-SA
  res.setHeader("access-control-allow-headers", "content-type");
  res.setHeader("access-control-allow-methods", "GET,POST,OPTIONS");
};
const send = (res, code, obj) => { cors(res); res.writeHead(code, { "content-type": "application/json" }); res.end(JSON.stringify(obj)); };
const clean = (r) => ({ title: r.title, section: r.section, entityRef: r.entity_ref, entityKey: r.entity_key, wikiUrl: r.wiki_url, text: r.text, sim: r.sim });

const c = pg();
await c.connect();
console.log(`[lore-service] connected to pgvector; ANTHROPIC_API_KEY ${process.env.ANTHROPIC_API_KEY ? "set" : "MISSING (ask disabled)"}; listening on :${PORT}`);

createServer(async (req, res) => {
  try {
    const url = new URL(req.url, "http://x");
    if (req.method === "OPTIONS") { cors(res); res.writeHead(204); return res.end(); }
    if (req.method === "GET" && url.pathname === "/health") return send(res, 200, { ok: true });

    if (req.method === "GET" && url.pathname === "/api/lore/search") {
      const q = url.searchParams.get("q");
      if (!q) return send(res, 400, { error: "missing ?q" });
      const k = Math.min(20, Math.max(1, parseInt(url.searchParams.get("k") || "8", 10)));
      return send(res, 200, { results: (await retrieve(c, q, k)).map(clean) });
    }

    if (req.method === "POST" && url.pathname === "/api/lore/ask") {
      let body = ""; for await (const ch of req) body += ch;
      const question = String(JSON.parse(body || "{}").question || "").trim();
      if (!question) return send(res, 400, { error: "missing question" });
      try { return send(res, 200, await askAgent(c, question)); }
      catch (e) { return send(res, 503, { error: e.message }); } // no key / Claude error → degrade to search
    }

    send(res, 404, { error: "not found" });
  } catch (e) {
    send(res, 500, { error: e.message });
  }
}).listen(PORT);
