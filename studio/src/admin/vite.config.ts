import { mergeConfig } from 'vite';

export default (config: any) => {
    // Important: always return the modified config
    return mergeConfig(config, {
        server: {
            allowedHosts: ['civstudio.com'],

            // If you are hosting this behind a proxy/tunnel,
            // you need to explicitly tell it to listen on all interfaces
            host: '0.0.0.0',
        },
    });
};