import type { Core } from '@strapi/strapi';

export default {
  /**
   * An asynchronous register function that runs before
   * your application is initialized.
   */
  register(/* { strapi }: { strapi: Core.Strapi } */) {},

  /**
   * An asynchronous bootstrap function that runs before
   * your application gets started.
   *
   * Seeds a stable local-dev super-admin so headless verification (tools/webverify) and offline dev
   * have known credentials without creating throwaway users or touching a real account. Runs ONLY in
   * development, and only when STRAPI_DEV_ADMIN_EMAIL/PASSWORD are set (defaults live in the gitignored
   * .env). Idempotent: skips if the account already exists.
   */
  async bootstrap({ strapi }: { strapi: Core.Strapi }) {
    if (process.env.NODE_ENV !== 'development') return;

    const email = process.env.STRAPI_DEV_ADMIN_EMAIL;
    const password = process.env.STRAPI_DEV_ADMIN_PASSWORD;
    if (!email || !password) return;

    try {
      const existing = await strapi.db
        .query('admin::user')
        .findOne({ where: { email: email.toLowerCase() } });
      if (existing) return;

      const superAdminRole = await strapi.service('admin::role').getSuperAdmin();
      if (!superAdminRole) {
        strapi.log.warn('[dev-admin] super-admin role not found yet; skipping seed');
        return;
      }

      await strapi.service('admin::user').create({
        email,
        firstname: 'Dev',
        lastname: 'Admin',
        password,
        isActive: true,
        roles: [superAdminRole.id],
      });
      strapi.log.info(`[dev-admin] seeded local dev super-admin ${email}`);
    } catch (err) {
      strapi.log.warn(`[dev-admin] seed skipped: ${(err as Error).message}`);
    }
  },
};
