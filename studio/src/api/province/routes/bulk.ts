export default {
  routes: [
    {
      method: 'POST',
      path: '/provinces/bulk',
      handler: 'province.bulkCreate',
      config: {},
    },
    {
      method: 'POST', // Using POST for custom bulk actions is standard
      path: '/provinces/bulk-links',
      handler: 'province.bulkLink',
      config: {
         // Remember to enable permissions for 'bulkLink' in Strapi Admin!
      },
    }
  ],
};