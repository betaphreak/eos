import { mergeConfig } from 'vite';

// The two build-time knobs the CivStudio admin reads (lib/serverApi.ts, lib/worldmap.ts). Both fall
// back to the live hosts when unset, so an ordinary build needs no env at all.
const VARS = ['VITE_CIVSTUDIO_SERVER', 'VITE_CIVSTUDIO_WORLDMAP'] as const;

export default (config: any) => {
    // `envPrefix` alone is NOT enough here, and it silently isn't: Strapi's admin build enumerates
    // which env vars it inlines (ADMIN_PATH, STRAPI_ADMIN_BACKEND_URL, …) and a VITE_-prefixed var
    // set on the command line never reaches the bundle — the build logs the list it used, and ours
    // is absent from it. Verified by building with VITE_CIVSTUDIO_SERVER set and finding the default
    // host still baked into dist/build/strapi-*.js. So define the values explicitly.
    //
    // Empty string rather than undefined when unset: `JSON.stringify(undefined)` is not valid define
    // output, and the readers use `RAW || '<default>'`, for which '' is correctly falsy.
    const define: Record<string, string> = {};
    for (const v of VARS) define[`import.meta.env.${v}`] = JSON.stringify(process.env[v] ?? '');

    // Important: always return the modified config
    return mergeConfig(config, {
        // Kept for .env-file support (Vite's own loading path); the `define` above covers the
        // command-line/process-env case that Strapi's allow-list would otherwise drop.
        envPrefix: ['STRAPI_ADMIN_', 'VITE_'],
        define,
        server: {
            allowedHosts: ['civstudio.com'],

            // If you are hosting this behind a proxy/tunnel,
            // you need to explicitly tell it to listen on all interfaces
            host: '0.0.0.0',
        },
    });
};
