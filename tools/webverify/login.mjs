// Shared Strapi-admin login helper for the local verify scripts.
//
// Uses the stable local-dev super-admin that studio/src/index.ts seeds in development
// (STRAPI_DEV_ADMIN_EMAIL / STRAPI_DEV_ADMIN_PASSWORD). Override here via STRAPI_ADMIN_EMAIL /
// STRAPI_ADMIN_PASSWORD if needed. Drives the login FORM (not the token API) so it stays robust
// across Strapi's session/refresh-token changes.
//
//   import { loginAdmin } from './login.mjs';
//   await loginAdmin(page, { base: 'http://localhost:1337' });

export const DEFAULT_BASE = process.env.STRAPI_BASE || 'http://localhost:1337';
export const ADMIN_EMAIL = process.env.STRAPI_ADMIN_EMAIL || 'dev@local.dev';
export const ADMIN_PASSWORD = process.env.STRAPI_ADMIN_PASSWORD || 'Devpass123!';

/**
 * Log a Playwright page into the Strapi admin. Resolves once the admin SPA is authenticated
 * (login form gone / off the /auth route). Throws on visible login failure.
 */
export async function loginAdmin(page, opts = {}) {
  const base = opts.base || DEFAULT_BASE;
  const email = opts.email || ADMIN_EMAIL;
  const password = opts.password || ADMIN_PASSWORD;

  await page.goto(`${base}/admin/auth/login`, { waitUntil: 'domcontentloaded', timeout: 90000 });
  await page.waitForSelector('input[name="email"]', { timeout: 60000 });

  // If a previous session is still valid the app redirects away from /auth — only fill if the form is up.
  if (await page.$('input[name="email"]')) {
    await page.fill('input[name="email"]', email);
    await page.fill('input[name="password"]', password);
    await Promise.all([
      page.waitForURL((u) => !/\/auth\//.test(u.toString()), { timeout: 60000 }).catch(() => {}),
      page.click('button[type="submit"]'),
    ]);
  }

  // Surface a bad-credentials error rather than timing out later.
  if (/\/auth\/login/.test(page.url())) {
    const err = await page.textContent('[data-strapi-error], [role="alert"]').catch(() => null);
    throw new Error(`admin login failed for ${email}${err ? ` — ${err.trim()}` : ''}`);
  }
  return { base, email };
}
