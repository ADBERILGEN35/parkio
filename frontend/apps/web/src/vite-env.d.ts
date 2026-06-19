/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL: string;
  readonly VITE_APP_ENV?: 'development' | 'test' | 'hosted-beta' | 'production';
  /** MapTiler API key — enables vector tiles. Absent ⇒ OSM raster fallback. */
  readonly VITE_MAPTILER_KEY?: string;
  /** MapTiler vector style id (default `streets-v2`). Prepared for style switching. */
  readonly VITE_MAPTILER_STYLE?: string;
  /** Raster fallback tile URL template (used only when no MapTiler key is set). */
  readonly VITE_MAP_TILE_URL?: string;
  /** Raster fallback attribution HTML (used only when no MapTiler key is set). */
  readonly VITE_MAP_TILE_ATTRIBUTION?: string;
  readonly VITE_GEOCODING_BASE_URL?: string;
  readonly VITE_FRONTEND_ERROR_REPORTING?: 'disabled' | 'console';
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
