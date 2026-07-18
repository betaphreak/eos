export default {
    routes: [
        {
            method: 'POST',
            path: '/cultures/bulk',
            handler: 'culture.bulkCreate',
            config: {},
        }
    ],
};