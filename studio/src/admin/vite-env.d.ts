/// <reference types="vite/client" />

// The build-time knobs the CivStudio admin reads. Declared so the source can use the EXACT
// `import.meta.env.VITE_...` form, which is what Vite's substitution matches — see vite.config.ts.
interface ImportMetaEnv {
  readonly VITE_CIVSTUDIO_SERVER?: string;
  readonly VITE_CIVSTUDIO_WORLDMAP?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
