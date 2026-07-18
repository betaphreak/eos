export default {
    routes: [
        {
            method: 'POST',
            path: '/countries/bulk',
            handler: 'country.bulkCreate',
            config: {},
        }
    ],
};