import { Box } from '@strapi/design-system';
import { worldMapUrl } from '../lib/worldmap';

/**
 * The world map viewer, embedded (docs/studio-control-plane-plan.md §D4).
 *
 * The viewer is a separate origin (an Azure Static Web App) that fetches its own bundle from the
 * game server, so the frame needs nothing from Strapi but permission to exist: `frame-src` in
 * config/middlewares.ts names the origin, because helmet's `useDefaults` otherwise leaves it falling
 * back to `default-src 'self'` and the iframe is blocked outright.
 *
 * `src` is a full URL, so changing it re-navigates the frame — that is how the page focuses a
 * province without a postMessage bridge.
 */
export default function WorldMapFrame({ src, height, title = 'CivStudio world map' }:
    { src?: string; height: number | string; title?: string }) {
  return (
    <Box
      hasRadius
      background="neutral0"
      style={{ overflow: 'hidden', height, width: '100%' }}
    >
      <iframe
        src={src ?? worldMapUrl(null, { embedded: true })}
        title={title}
        // no allow-* beyond the default: the viewer only needs to render and fetch its own data
        loading="lazy"
        style={{ border: 0, width: '100%', height: '100%', display: 'block' }}
      />
    </Box>
  );
}
