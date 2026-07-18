import { factories } from '@strapi/strapi';

export default factories.createCoreController('api::province-relation.province-relation', ({ strapi }) => ({
  
  async bulkCreate(ctx) {
    const { data } = ctx.request.body;
    
    if (!Array.isArray(data)) {
      return ctx.badRequest('Expected an array of province relations under the "data" key.');
    }

    let createdCount = 0;
    const errors: any[] = [];
    
    // Loop and insert locally on the Node server directly to the DB
    for (const item of data) {
      try {
        await strapi.documents('api::province-relation.province-relation').create({
          data: item
          // Note: status: 'published' is omitted here because your schema has draftAndPublish: false
        });
        createdCount++;
      } catch (err) {
        strapi.log.error(`Failed to create province relation ${item.name}:`, err);
        errors.push({ name: item.name, error: err.message });
      }
    }
    
    // Return a lightweight summary instead of thousands of objects
    return { 
      data: { 
        created: createdCount,
        errors: errors.length > 0 ? errors : undefined
      } 
    };
  }
}));