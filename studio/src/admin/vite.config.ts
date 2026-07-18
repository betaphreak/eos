import { mergeConfig } from 'vite';

export default (config: any) => {
    // Important: always return the modified config
    return mergeConfig(config, {
        // Expose VITE_-prefixed build env (alongside Strapi's own STRAPI_ADMIN_) to the admin bundle,
        // so the server-ops widgets can read VITE_CIVSTUDIO_SERVER (defaults to dev.civstudio.com).
        envPrefix: ['STRAPI_ADMIN_', 'VITE_'],
        server: {
            allowedHosts: ['civstudio.com'],

            // If you are hosting this behind a proxy/tunnel,
            // you need to explicitly tell it to listen on all interfaces
            host: '0.0.0.0',
        },
    });
};