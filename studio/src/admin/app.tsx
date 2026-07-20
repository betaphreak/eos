import type { StrapiApp } from '@strapi/strapi/admin';
import { Server, Command, Earth } from '@strapi/icons';
import { civstudioTheme } from './theme';
// `?url` forces a URL string (not an svgr React component) regardless of the admin's SVG plugin,
// which is what config.menu/auth/head expect.
import Favicon from './assets/favicon.svg?url';
import MenuLogo from './assets/mark.svg?url';
import AuthLogo from './assets/wordmark.svg?url';
import OpenInWorldMap from './components/OpenInWorldMap';

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
    // Rebrand the login copy off "Strapi".
    translations: {
      en: {
        'Auth.form.welcome.title': 'Welcome to CivStudio',
        'Auth.form.welcome.subtitle': 'Sign in to the administer content',
        // The admin error boundary (ErrorElement) already logs the error to the console and offers a
        // "Copy to clipboard" button with the full stack — but its default copy tells you to "notify
        // your technical team" / open a Strapi GitHub issue, useless for a solo operator. Rewrite it to
        // point at where the actual error already is. (We can't inject the dynamic message via a static
        // translation; the console + Copy button carry it.)
        'app.error': 'Something went wrong',
        'app.error.message':
          'The actual error is in your browser console (press F12 → Console). Use “Copy to clipboard” below to grab the full stack trace.',
      },
    },
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
      {
        id: 'civstudio-worldmap',
        icon: Earth,
        title: { id: 'civstudio.widget.worldMap.title', defaultMessage: 'World map' },
        component: async () => (await import('./components/WorldMapWidget')).default,
      },
    ]);

    // The Sessions page — the roomier view the widget rows link into (docs/studio-control-plane-plan.md
    // §C2). `to` is RELATIVE to the admin root on purpose: Router#addMenuLink strips a leading slash
    // and warns. It registers the route as `<to>/*`, so the page owns its own nested routes (:id).
    // Like `widgets`, this must be in `register` — `bootstrap`'s argument is a Pick without it.
    app.addMenuLink?.({
      to: 'civstudio-sessions',
      icon: Command,
      intlLabel: { id: 'civstudio.menu.sessions', defaultMessage: 'Sessions' },
      permissions: [], // any authenticated admin; the game server enforces its own ROLE_ADMIN
      Component: () => import('./pages/Sessions'),
    });

    // The world map as its own admin destination (§D4). Deep-linkable: /admin/civstudio-map?p=4411.
    app.addMenuLink?.({
      to: 'civstudio-map',
      icon: Earth,
      intlLabel: { id: 'civstudio.menu.worldMap', defaultMessage: 'World map' },
      permissions: [],
      Component: () => import('./pages/WorldMap'),
    });
  },
  bootstrap(app: any) {
    // "Open in world map" on the province edit view (docs/studio-control-plane-plan.md §D1). This
    // goes in `bootstrap`, not `register`: the injection zone belongs to the content-manager PLUGIN,
    // which is loaded by the time bootstrap runs — and `getPlugin` is one of the few things
    // bootstrap's restricted Pick does give us. The component self-filters to provinces, since the
    // zone is shared by every content type.
    app.getPlugin?.('content-manager')?.injectComponent?.('editView', 'right-links', {
      name: 'civstudio-open-in-worldmap',
      Component: OpenInWorldMap,
    });

    // Make the CivStudio dark (navy) look the DEFAULT appearance: Strapi falls back to 'system' when
    // STRAPI_THEME is unset — seed 'dark' the first time so the branded dark theme is the first
    // impression. Users can still switch (Profile → Appearance); we only seed when unset.
    try {
      if (!localStorage.getItem('STRAPI_THEME')) localStorage.setItem('STRAPI_THEME', 'dark');
    } catch {
      /* private mode / storage disabled — fall back to Strapi's default */
    }

    // DOM-only tweaks the theme tokens can't reach (safe in bootstrap).
    const style = document.createElement('style');
    style.innerHTML = `
      /* Hide upsell / EE links.
         Every locked Enterprise feature routes through a "purchase-" settings page — Content History,
         Releases, Review Workflows, Single Sign-On and Audit Logs are all
         /admin/settings/purchase-<feature>. Matching that prefix covers the set AND anything Strapi
         adds later, which enumerating did not: the previous rules named /settings/audit-logs,
         /settings/sso and /settings/review-workflows, routes Strapi had already renamed, so they had
         quietly stopped hiding anything. The :has() rule takes the list item with it, so the section
         does not keep a gap where the entry was. */
      a[href*="strapi.io/pricing"], div:has(> a[href*="strapi.io/pricing"]) { display: none !important; }
      a[href*="/settings/purchase-"],
      li:has(> a[href*="/settings/purchase-"]),
      li:has(> div > a[href*="/settings/purchase-"]) { display: none !important; }

      /* match web/ chrome: system-ui body (the brand serif lives in the SVG logos, as in web/) */
      body, button, input, select, textarea {
        font-family: system-ui, "Segoe UI", Roboto, -apple-system, sans-serif;
      }

      /* (Solid-button text ink is handled by the buttonNeutral0 theme token — see theme.ts.) */

      /* Homepage widgets: pin our two ops widgets to half-width (6/12). Strapi's default-layout
         algorithm (createDefaultWidgetWidths) gives every widget 6 columns EXCEPT, when the total
         widget count is odd, the last-registered one — which is ours — gets a full 12. There is no
         width field on the widget registration API (5.42), so force the span here. The Grid.Item (the
         grid child carrying grid-column) is the DIRECT parent of the WidgetRoot that holds
         data-strapi-widget-id — target it via :has(> …), scoped under the grid container. */
      [data-strapi-grid-container] :has(> [data-strapi-widget-id="global::civstudio-server-ops"]),
      [data-strapi-grid-container] :has(> [data-strapi-widget-id="global::civstudio-sessions"]) {
        grid-column: span 6 !important;
      }
      /* the map is the exception — it wants the whole row, whatever the widget count works out to */
      [data-strapi-grid-container] :has(> [data-strapi-widget-id="global::civstudio-worldmap"]) {
        grid-column: span 12 !important;
      }

      /* ===== Login: web/-style cinematic dark splash + gold-hairline glass card =====
         Pinned dark and self-contained (web/'s splash is always dark, independent of the UI theme).
         Scoped to the AUTH pages via body:not(:has(nav)) — authed pages render the MainNav <nav>, the
         login page doesn't — so these login-structural rules never touch the content-manager layout.
         Rules are under #main-content so the ID out-specifies styled-components' single-class rules. */
      body:not(:has(nav)) {
        background:
          radial-gradient(1200px 620px at 50% -12%, rgba(230,176,74,.12), transparent 60%),
          radial-gradient(820px 520px at 50% 118%, rgba(110,168,255,.05), transparent 60%),
          linear-gradient(180deg, #0c111b 0%, #080b12 100%) !important;
      }
      body:not(:has(nav)) #main-content { background: transparent !important; }
      body:not(:has(nav)) #main-content > div:first-child {
        background: rgba(13,17,25,.55) !important;
        -webkit-backdrop-filter: blur(9px); backdrop-filter: blur(9px);
        border: 1px solid rgba(230,176,74,.42) !important;
        border-radius: 16px !important; box-shadow: 0 20px 60px rgba(0,0,0,.55) !important;
        padding: 40px 44px 34px !important;
      }
      body:not(:has(nav)) #main-content h1 {
        font-family: Georgia, "Times New Roman", serif !important;
        color: #f2e6cf !important; font-weight: 600 !important; letter-spacing: .02em !important;
      }
      body:not(:has(nav)) #main-content label,
      body:not(:has(nav)) #main-content > div:first-child > div span,
      body:not(:has(nav)) #main-content p { color: #aeb9ca !important; }
      body:not(:has(nav)) #main-content input {
        background: rgba(190,205,230,.05) !important; color: #eef2f8 !important;
        border-color: rgba(190,205,230,.18) !important;
      }
      body:not(:has(nav)) #main-content input::placeholder { color: #6b7688 !important; }
      body:not(:has(nav)) #main-content a { color: #e6b04a !important; }
      body:not(:has(nav)) #main-content img { filter: drop-shadow(0 2px 14px rgba(230,176,74,.28)); }
      body:not(:has(nav)) #main-content form button,
      body:not(:has(nav)) #main-content form button span { color: #1a1206 !important; }
    `;
    document.head.appendChild(style);
  },
};
