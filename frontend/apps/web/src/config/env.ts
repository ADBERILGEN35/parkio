import { z } from 'zod';

export type AppEnvironment = 'development' | 'test' | 'hosted-beta' | 'production';
export type FrontendErrorReportingProvider = 'disabled' | 'console';

const LOCAL_API_BASE_URL = 'http://localhost:8080/api/v1';
const LOCAL_GEOCODING_BASE_URL = 'https://nominatim.openstreetmap.org';
const LOCAL_MAP_TILE_URL = 'https://tile.openstreetmap.org/{z}/{x}/{y}.png';
const LOCAL_MAP_TILE_ATTRIBUTION =
  '© <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap</a> contributors';

const appEnvSchema = z.enum(['development', 'test', 'hosted-beta', 'production']);

const blankToUndefined = (value: unknown) =>
  typeof value === 'string' && value.trim() === '' ? undefined : value;

const optionalUrl = z.preprocess(blankToUndefined, z.string().trim().url().optional());
const optionalNonEmpty = z.preprocess(blankToUndefined, z.string().trim().min(1).optional());

const rawEnvSchema = z.object({
  MODE: z.string().optional(),
  PROD: z.boolean().optional(),
  DEV: z.boolean().optional(),
  VITE_APP_ENV: appEnvSchema.optional(),
  VITE_API_BASE_URL: optionalUrl,
  VITE_MAPTILER_KEY: optionalNonEmpty,
  VITE_MAPTILER_STYLE: optionalNonEmpty,
  VITE_MAP_TILE_URL: optionalNonEmpty,
  VITE_MAP_TILE_ATTRIBUTION: optionalNonEmpty,
  VITE_GEOCODING_BASE_URL: optionalUrl,
  VITE_FRONTEND_ERROR_REPORTING: z.enum(['disabled', 'console']).optional(),
});

export interface FrontendConfig {
  appEnv: AppEnvironment;
  isProductionLike: boolean;
  apiBaseUrl: string;
  map: {
    maptilerKey: string;
    maptilerStyle: string;
    rasterTileUrl: string;
    rasterAttribution: string;
  };
  geocoding: {
    baseUrl: string;
  };
  errorReporting: {
    provider: FrontendErrorReportingProvider;
  };
}

function resolveAppEnv(raw: z.infer<typeof rawEnvSchema>): AppEnvironment {
  if (raw.VITE_APP_ENV) return raw.VITE_APP_ENV;
  if (raw.MODE === 'test') return 'test';
  return raw.PROD ? 'production' : 'development';
}

function requireInProductionLike(value: string | undefined, key: string, appEnv: AppEnvironment): string {
  if (value) return value;
  if (appEnv === 'production' || appEnv === 'hosted-beta') {
    throw new Error(`${key} is required when VITE_APP_ENV=${appEnv}.`);
  }
  return '';
}

export function createFrontendConfig(env: ImportMetaEnv): FrontendConfig {
  const raw = rawEnvSchema.parse(env);
  const appEnv = resolveAppEnv(raw);
  const isProductionLike = appEnv === 'production' || appEnv === 'hosted-beta';

  const apiBaseUrl = raw.VITE_API_BASE_URL ?? (isProductionLike ? undefined : LOCAL_API_BASE_URL);
  const geocodingBaseUrl =
    raw.VITE_GEOCODING_BASE_URL ?? (isProductionLike ? undefined : LOCAL_GEOCODING_BASE_URL);

  if (!apiBaseUrl) {
    throw new Error(`VITE_API_BASE_URL is required when VITE_APP_ENV=${appEnv}.`);
  }
  const maptilerKey = requireInProductionLike(raw.VITE_MAPTILER_KEY, 'VITE_MAPTILER_KEY', appEnv);
  if (!geocodingBaseUrl) {
    throw new Error(`VITE_GEOCODING_BASE_URL is required when VITE_APP_ENV=${appEnv}.`);
  }

  return {
    appEnv,
    isProductionLike,
    apiBaseUrl,
    map: {
      maptilerKey,
      maptilerStyle: raw.VITE_MAPTILER_STYLE ?? 'streets-v2',
      rasterTileUrl: raw.VITE_MAP_TILE_URL ?? LOCAL_MAP_TILE_URL,
      rasterAttribution: raw.VITE_MAP_TILE_ATTRIBUTION ?? LOCAL_MAP_TILE_ATTRIBUTION,
    },
    geocoding: {
      baseUrl: geocodingBaseUrl.replace(/\/+$/, ''),
    },
    errorReporting: {
      provider: raw.VITE_FRONTEND_ERROR_REPORTING ?? 'disabled',
    },
  };
}

export const frontendConfig = createFrontendConfig(import.meta.env);
