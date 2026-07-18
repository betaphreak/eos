import type { StrapiApp } from '@strapi/strapi/admin';
import { Server, Command } from '@strapi/icons';

export default {
  config: {
    locales: [],
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
    // Keep the existing CSS injection (DOM-only — safe in bootstrap).
    const style = document.createElement('style');
    style.innerHTML = `
      a[href*="strapi.io/pricing"], div:has(> a[href*="strapi.io/pricing"]) { display: none !important; }
      a[href$="/settings/audit-logs"], a[href$="/settings/review-workflows"],
      a[href$="/settings/sso"], a[href$="/settings/purchase-content"] { display: none !important; }

      /* Style for our custom button */
      .custom-purge-btn {
        background: #d02b2b;
        color: white;
        border: none;
        padding: 8px 16px;
        border-radius: 4px;
        cursor: pointer;
        font-weight: 600;
        margin-right: 10px;
      }
      .custom-purge-btn:hover { background: #ad2020; }
    `;
    document.head.appendChild(style);
  },
};