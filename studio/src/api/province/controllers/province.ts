import { factories } from '@strapi/strapi';

export default factories.createCoreController('api::province.province', ({ strapi }) => ({

  async bulkCreate(ctx) {
    const { data } = ctx.request.body;

    if (!Array.isArray(data)) {
      return ctx.badRequest('Expected an array of provinces under the "data" key.');
    }

    const createdProvinces: Array<{ provinceId: number; documentId: string }> = [];

    // Loop and insert locally on the Node server directly to the DB
    for (const item of data) {
      try {
        const entry = await strapi.documents('api::province.province').create({
          data: item,
          status: 'published', // Automatically publish if Draft & Publish is enabled
        });

        createdProvinces.push({
          provinceId: entry.provinceId,
          documentId: entry.documentId
        });
      } catch (err) {
        strapi.log.error(`Failed to create province ${item.provinceId}:`, err);
      }
    }

    // Return a lightweight mapping of in-game IDs to their new Strapi IDs
    return { data: createdProvinces };
  },

  async bulkLink(ctx) {
    const { data } = ctx.request.body;

    if (!Array.isArray(data)) {
      return ctx.badRequest('Expected an array of link objects under the "data" key.');
    }

    let updatedCount = 0;

    // Loop and update relations locally on the server
    for (const item of data) {
      if (!item.documentId || !item.neighbors) continue;

      try {
        await strapi.documents('api::province.province').update({
          documentId: item.documentId,
          data: {
            neighbors: item.neighbors
          } as any // <-- FIX: Bypasses the TS compilation error
        });
        updatedCount++;
      } catch (err) {
        strapi.log.error(`Failed to update neighbors for province ${item.documentId}:`, err);
      }
    }

    return { data: { updated: updatedCount } };
  }

}));