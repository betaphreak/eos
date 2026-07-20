import type { Core } from '@strapi/strapi';

export default [
  'strapi::errors',
  {
    name: 'strapi::security',
    config: {
      contentSecurityPolicy: {
        useDefaults: true,
        directives: {
          'connect-src': ["'self'", 'https:'],
          'img-src': ["'self'", 'data:', 'blob:', 'https://civstudiostore.blob.core.windows.net'],
          'media-src': ["'self'", 'data:', 'blob:', 'https://civstudiostore.blob.core.windows.net'],
          // The admin embeds the world map viewer (its own origin, an Azure Static Web App) as an
          // iframe — the homepage widget and the World map page. Helmet's `useDefaults` leaves
          // frame-src falling back to `default-src 'self'`, which blocks it outright, so the viewer
          // origin has to be named. `child-src` is the deprecated alias older browsers read.
          // localhost is here for `npm run develop` against a local web/dev-server.mjs.
          'frame-src': ["'self'", 'https://anbennar.civstudio.com', 'http://localhost:3000'],
          'child-src': ["'self'", 'https://anbennar.civstudio.com', 'http://localhost:3000'],
          upgradeInsecureRequests: null,
        },
      },
    },
  },
  'strapi::cors',
  'strapi::poweredBy',
  'strapi::logger',
  'strapi::query',
  'strapi::body',
  'strapi::session',
  'strapi::favicon',
  'strapi::public',
];
