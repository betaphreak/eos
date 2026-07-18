import type { StrapiApp } from '@strapi/strapi/admin';
import { Server, Command } from '@strapi/icons';
import { civstudioTheme } from './theme';
// `?url` forces a URL string (not an svgr React component) regardless of the admin's SVG plugin,
// which is what config.menu/auth/head expect.
import Favicon from './assets/favicon.svg?url';
import MenuLogo from './assets/mark.svg?url';
import AuthLogo from './assets/wordmark.svg?url';

export default {
  config: {
    locales: [],
    // CivStudio brand theme — ported from web/styles.css (see ./theme.ts).
    theme: civstudioTheme,
    // Brand assets: gold "C" monogram in the menu, "CivStudio" wordmark on the login page.
    menu: { logo: MenuLogo },
    auth: { logo: AuthLogo },
    head: { favicon: Favicon },
    // Quiet the Strapi-branding noise, matching a self-hosted product feel.
    tutorials: false,
    notifications: { releases: false },
  },
  // Widget registration MUST live in `register`, not `bootstrap`: the admin passes the full
  // StrapiApp here (so `app.widgets` exists), whereas the `bootstrap` hook's argument is a
  // restricted Pick that has NO `widgets` — calling `app.widgets.register` there throws and blanks
  // the entire admin SPA. The optional-chaining guard is belt-and-braces so a future API shift can
  // never take the whole admin down again.
  register(app: any) {
    // CivStudio server ops as admin homepage widgets — the replacement for the retired
    // web/admin.html console. They call the game server's gated /api/admin/** + /api/sessions/**
    // cross-origin with the operator's server session (see src/admin/lib/serverApi.ts).
    app.widgets?.register?.([
      {
        id: 'civstudio-server-ops',
        icon: Server,
        title: { id: 'civstudio.widget.serverOps.title', defaultMessage: 'Server ops' },
        component: async () => (await import('./components/ServerOpsWidget')).default,
      },
      {
        id: 'civstudio-sessions',
        icon: Command,
        title: { id: 'civstudio.widget.sessions.title', defaultMessage: 'Live sessions' },
        component: async () => (await import('./components/SessionsWidget')).default,
      },
    ]);
  },
  bootstrap() {
    // DOM-only tweaks the theme tokens can't reach (safe in bootstrap).
    const style = document.createElement('style');
    style.innerHTML = `
      /* hide upsell / EE links */
      a[href*="strapi.io/pricing"], div:has(> a[href*="strapi.io/pricing"]) { display: none !important; }
      a[href$="/settings/audit-logs"], a[href$="/settings/review-workflows"],
      a[href$="/settings/sso"], a[href$="/settings/purchase-content"] { display: none !important; }

      /* match web/ chrome: system-ui body (the brand serif lives in the SVG logos, as in web/) */
      body, button, input, select, textarea {
        font-family: system-ui, "Segoe UI", Roboto, -apple-system, sans-serif;
      }

      /* gold primary buttons want dark ink, not white (web/'s convention — a bright-gold fill in dark
         mode is unreadable under white text). Scoped to primary-variant buttons by their gold bg. */
      button[data-variant="default"], button[data-variant="primary"] { color: #1a1206 !important; }
    `;
    document.head.appendChild(style);
  },
};
