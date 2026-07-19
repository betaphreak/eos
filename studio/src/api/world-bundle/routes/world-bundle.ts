/**
 * Custom collectionless route: the consolidated world-bundle the engine boots from (Phase 4 of the
 * studio-datamodel rebuild — docs/studio-datamodel-rebuild-plan.md). One gzipped, version-stamped
 * payload that projects the normalized Strapi content back into the FLAT per-dataset shapes the
 * engine's existing loaders already parse (so the engine only swaps its byte source, file → HTTP).
 *
 * Auth: gated by a shared secret when WORLD_BUNDLE_TOKEN is set (prod); open when unset (local dev).
 */
export default {
  routes: [
    {
      method: 'GET',
      path: '/world-bundle',
      handler: 'world-bundle.index',
      config: { auth: false }, // controller enforces the WORLD_BUNDLE_TOKEN shared secret itself
    },
    {
      method: 'GET',
      path: '/world-bundle/version',
      handler: 'world-bundle.version',
      config: { auth: false }, // cheap { mapVersion, contentVersion } probe for client revalidation
    },
  ],
};
