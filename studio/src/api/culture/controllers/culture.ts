import { factories } from '@strapi/strapi';

export default factories.createCoreController('api::culture.culture', ({ strapi }) => ({


// =========================================
// POST /api/cultures/bulk
// =========================================
    async bulkCreate(ctx) {
        try {
            // Expecting a JSON array of name objects in the request body
            const {data} = ctx.request.body;

            if (!Array.isArray(data)) {
                return ctx.badRequest('Expected an array of culture objects in the "data" wrapper.');
            }

            const createdRecords = [];

            for (const item of data) {
                // Using the Strapi v5 Document Service API
                const record = await strapi.documents('api::culture.culture').create({
                    data: item,
                });
                createdRecords.push({documentId: record.documentId, name: record.name});
            }

            // Return a summary to the Java client
            return ctx.send({
                message: `Successfully created ${createdRecords.length} cultures.`,
                data: createdRecords
            });

        } catch (error) {
            ctx.throw(500, `Bulk creation failed: ${error.message}`);
        }
    },
}));