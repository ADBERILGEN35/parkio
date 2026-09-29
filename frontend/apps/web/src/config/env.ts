import { z } from 'zod';

export type AppEnvironment = 'development' | 'test' | 'hosted-beta' | 'invite-production' | 'production';
export type FrontendErrorReportingProvider = 'disabled' | 'console';
export type RegistrationMode = 'CLOSED' | 'INVITE' | 'OPEN';

const LOCAL_API_BASE_URL = 'http://localhost:8080/api/v1';
const LOCAL_MAP_TILE_URL = 'https://tile.openstreetmap.org/{z}/{x}/{y}.png';
const LOCAL_MAP_TILE_ATTRIBUTION =
  '© <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap</a> contributors';

const appEnvSchema = z.enum(['development', 'test', 'hosted-beta', 'invite-production', 'production']);

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
  VITE_FRONTEND_ERROR_REPORTING: z.enum(['disabled', 'console']).optional(),
  VITE_SMART_RETURN_ENABLED: z.enum(['true', 'false']).optional(),
  /** WEB-MUNI-01 — municipal facility map discovery. Default off everywhere. */
  VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED: z.enum(['true', 'false']).optional(),
  /** WP-SPA-08 — web Smart Parking Assistant. Explicit opt-in only. */
  VITE_SMART_PARKING_ASSISTANT_ENABLED: z.enum(['true', 'false']).optional(),
  /** GOOGLE-STARTUP-REAPPLY-01C — anonymous read-only explore. */
  VITE_PUBLIC_EXPLORE_ENABLED: z.enum(['true', 'false']).optional(),
  /** Safe first paint while the runtime registration mode is fetched. */
  VITE_REGISTRATION_MODE: z.enum(['CLOSED', 'INVITE', 'OPEN', 'closed', 'invite', 'open']).optional(),
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
  errorReporting: {
    provider: FrontendErrorReportingProvider;
  };
  registrationModeBootstrap: RegistrationMode;
  features: {
    smartReturn: boolean;
    /**
     * When true, `/map` loads municipal facilities as a separate inventory
     * (`WEB_MUNICIPAL_DISCOVERY_ENABLED` / `VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED`).
     * Default false; hosted-beta and invite-production enable only under their
     * controlled release policies; broad production stays false.
     */
    municipalDiscovery: boolean;
    /**
     * When true, `/map` shows the destination-first Smart Parking Assistant
     * (`VITE_SMART_PARKING_ASSISTANT_ENABLED`). Default false; independent of municipalDiscovery.
     */
    smartParkingAssistant: boolean;
    publicExplore: boolean;
  };
}

function resolveAppEnv(raw: z.infer<typeof rawEnvSchema>): AppEnvironment {
  if (raw.VITE_APP_ENV) return raw.VITE_APP_ENV;
  if (raw.MODE === 'test') return 'test';
  return raw.PROD ? 'production' : 'development';
}

function requireInProductionLike(value: string | undefined, key: string, appEnv: AppEnvironment): string {
  if (value) return value;
  if (isProductionLikeEnvironment(appEnv)) {
    throw new Error(`${key} is required when VITE_APP_ENV=${appEnv}.`);
  }
  return '';
}

function isProductionLikeEnvironment(appEnv: AppEnvironment): boolean {
  return appEnv === 'production' || appEnv === 'hosted-beta' || appEnv === 'invite-production';
}

export function createFrontendConfig(env: ImportMetaEnv): FrontendConfig {
  const raw = rawEnvSchema.parse(env);
  const appEnv = resolveAppEnv(raw);
  const isProductionLike = isProductionLikeEnvironment(appEnv);

  const apiBaseUrl = raw.VITE_API_BASE_URL ?? (isProductionLike ? undefined : LOCAL_API_BASE_URL);

  if (!apiBaseUrl) {
    throw new Error(`VITE_API_BASE_URL is required when VITE_APP_ENV=${appEnv}.`);
  }
  const maptilerKey = requireInProductionLike(raw.VITE_MAPTILER_KEY, 'VITE_MAPTILER_KEY', appEnv);

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
    errorReporting: {
      provider: raw.VITE_FRONTEND_ERROR_REPORTING ?? 'disabled',
    },
    registrationModeBootstrap: raw.VITE_REGISTRATION_MODE
      ? (raw.VITE_REGISTRATION_MODE.toUpperCase() as RegistrationMode)
      : raw.MODE === 'test'
        ? 'OPEN'
        : 'CLOSED',
    features: {
      smartReturn: raw.VITE_SMART_RETURN_ENABLED === undefined
        ? !isProductionLike
        : raw.VITE_SMART_RETURN_ENABLED === 'true',
      // Explicit opt-in only — never default on for a production-like environment.
      municipalDiscovery: raw.VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED === 'true',
      smartParkingAssistant: raw.VITE_SMART_PARKING_ASSISTANT_ENABLED === 'true',
      publicExplore: raw.VITE_PUBLIC_EXPLORE_ENABLED === 'true',
    },
  };
}

export const frontendConfig = createFrontendConfig(import.meta.env);
