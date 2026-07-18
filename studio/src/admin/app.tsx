import type { StrapiApp } from '@strapi/strapi/admin';

export default {
  config: {
    locales: [],
  },
  bootstrap(app: any) {
    // 1. Keep your existing CSS injection
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