/**
 * world-bundle controller — serves the consolidated, version-stamped world bundle. The projection +
 * version-keyed cache live in services/world-bundle.ts; this just handles auth, content-encoding, and
 * HTTP caching (ETag/304 keyed on content-version, so an up-to-date client skips the 2 MB entirely).
 */
export default {
  async index(ctx: any) {
    if (!authorized(ctx)) return ctx.unauthorized('bad or missing world-bundle token');

    const cached = await strapi
      .service('api::world-bundle.world-bundle')
      .serialized(String(ctx.request.query.fresh || '') === '1');

    const etag = `"${cached.version}"`;
    ctx.set('ETag', etag);
    ctx.set('Cache-Control', 'no-cache'); // cache, but revalidate (the version check is cheap)
    if (ctx.request.header['if-none-match'] === etag) {
      ctx.status = 304; // client already has this content-version
      return;
    }

    if (/\bgzip\b/.test(String(ctx.request.header['accept-encoding'] || ''))) {
      ctx.set('Content-Encoding', 'gzip');
      ctx.type = 'application/json';
      ctx.body = cached.gzip;
    } else {
      ctx.type = 'application/json';
      ctx.body = cached.json;
    }
  },

  /** Cheap version probe: { mapVersion, contentVersion } — lets a client revalidate without the bundle. */
  async version(ctx: any) {
    if (!authorized(ctx)) return ctx.unauthorized('bad or missing world-bundle token');
    ctx.body = await strapi.service('api::world-bundle.world-bundle').meta();
  },
};

/** Shared-secret gate: enforced only when WORLD_BUNDLE_TOKEN is set (so local dev stays open). */
function authorized(ctx: any): boolean {
  const need = process.env.WORLD_BUNDLE_TOKEN;
  if (!need) return true;
  const got = String(ctx.request.header.authorization || '').replace(/^Bearer\s+/i, '');
  return got === need;
}
