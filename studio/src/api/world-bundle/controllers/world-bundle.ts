/**
 * world-bundle controller — assembles + serves the consolidated, version-stamped world bundle.
 * The projection (normalized Strapi → flat committed-JSON shapes) lives in services/projection.ts.
 */
import zlib from 'node:zlib';

export default {
  async index(ctx: any) {
    // Shared-secret gate: enforced only when WORLD_BUNDLE_TOKEN is set (so local dev stays open).
    const need = process.env.WORLD_BUNDLE_TOKEN;
    if (need) {
      const got = String(ctx.request.header.authorization || '').replace(/^Bearer\s+/i, '');
      if (got !== need) return ctx.unauthorized('bad or missing world-bundle token');
    }

    const bundle = await strapi.service('api::world-bundle.world-bundle').build();
    const json = JSON.stringify(bundle);

    ctx.set('Cache-Control', 'no-cache');
    if (/\bgzip\b/.test(String(ctx.request.header['accept-encoding'] || ''))) {
      ctx.set('Content-Encoding', 'gzip');
      ctx.type = 'application/json';
      ctx.body = zlib.gzipSync(json);
    } else {
      ctx.type = 'application/json';
      ctx.body = json;
    }
  },
};
