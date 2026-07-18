export default {
  routes: [
    {
      method: 'POST',
      path: '/province-relations/bulk',
      handler: 'province-relation.bulkCreate',
      config: {
        // Leave empty to use standard Strapi role permissions.
        // Don't forget to enable 'bulkCreate' for the API token in Strapi Admin!
      },
    },
  ],
};