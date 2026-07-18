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
    // Rebrand the login copy off "Strapi".
    translations: {
      en: {
        'Auth.form.welcome.title': 'Welcome to CivStudio',
        'Auth.form.welcome.subtitle': 'Sign in to the administer content',
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
    ]);
  },
  bootstrap() {
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
      /* hide upsell / EE links */
      a[href*="strapi.io/pricing"], div:has(> a[href*="strapi.io/pricing"]) { display: none !important; }
      a[href$="/settings/audit-logs"], a[href$="/settings/review-workflows"],
      a[href$="/settings/sso"], a[href$="/settings/purchase-content"] { display: none !important; }

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
