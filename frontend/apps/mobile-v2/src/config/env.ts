import { z } from 'zod';

/**
 * Typed, validated runtime config.
 *
 * All client config travels through `EXPO_PUBLIC_*` env vars, which Expo inlines
 * at build time. Local dev reads `.env.local`; hosted-beta / production values
 * are injected by the matching EAS build profile. No secrets live here — only
 * public endpoints and feature flags.
 */
export type AppEnvironment = 'development' | 'hosted-beta' | 'production';

const blankToUndefined = (value: unknown) =>
  typeof value === 'string' && value.trim() === '' ? undefined : value;

/**
 * Accept only the literal strings `true` / `false`. Blank or any other value is
 * treated as missing (safe default) — never crash startup on a typo.
 */
const optionalTrueFalseFlag = z.preprocess((value) => {
  const cleaned = blankToUndefined(value);
  if (cleaned === undefined) return undefined;
  if (typeof cleaned === 'string' && (cleaned === 'true' || cleaned === 'false')) {
    return cleaned;
  }
  return undefined;
}, z.enum(['true', 'false']).optional());

const rawSchema = z.object({
  EXPO_PUBLIC_APP_ENV: z.enum(['development', 'hosted-beta', 'production']).optional(),
  EXPO_PUBLIC_API_BASE_URL: z.preprocess(blankToUndefined, z.string().url().optional()),
  EXPO_PUBLIC_SMART_RETURN_ENABLED: z.enum(['true', 'false']).optional(),
  EXPO_PUBLIC_MUNICIPAL_DISCOVERY_ENABLED: optionalTrueFalseFlag,
});

export interface MobileConfig {
  appEnv: AppEnvironment;
  isProductionLike: boolean;
  apiBaseUrl: string;
  features: {
    smartReturn: boolean;
    /**
     * Guarded municipal facility discovery (MOBILE-MUNI-V2-01).
     * Default off when unset — including development — so flag-off produces no
     * municipal network traffic until explicitly enabled.
     */
    municipalDiscovery: boolean;
  };
}

export function createMobileConfig(env: Record<string, string | undefined>): MobileConfig {
  const raw = rawSchema.parse({
    EXPO_PUBLIC_APP_ENV: env.EXPO_PUBLIC_APP_ENV,
    EXPO_PUBLIC_API_BASE_URL: env.EXPO_PUBLIC_API_BASE_URL,
    EXPO_PUBLIC_SMART_RETURN_ENABLED: env.EXPO_PUBLIC_SMART_RETURN_ENABLED,
    EXPO_PUBLIC_MUNICIPAL_DISCOVERY_ENABLED: env.EXPO_PUBLIC_MUNICIPAL_DISCOVERY_ENABLED,
  });

  const appEnv: AppEnvironment = raw.EXPO_PUBLIC_APP_ENV ?? 'development';
  const isProductionLike = appEnv === 'production' || appEnv === 'hosted-beta';

  const apiBaseUrl = raw.EXPO_PUBLIC_API_BASE_URL;
  if (!apiBaseUrl) {
    throw new Error(
      `EXPO_PUBLIC_API_BASE_URL is required when EXPO_PUBLIC_APP_ENV=${appEnv}. Set it explicitly for every build profile.`,
    );
  }

  return {
    appEnv,
    isProductionLike,
    apiBaseUrl,
    features: {
      smartReturn:
        raw.EXPO_PUBLIC_SMART_RETURN_ENABLED === undefined
          ? !isProductionLike
          : raw.EXPO_PUBLIC_SMART_RETURN_ENABLED === 'true',
      // Explicit opt-in only. Missing / malformed / "false" → disabled.
      municipalDiscovery: raw.EXPO_PUBLIC_MUNICIPAL_DISCOVERY_ENABLED === 'true',
    },
  };
}

// Static `process.env.EXPO_PUBLIC_*` member expressions are required here:
// babel-preset-expo inlines only these at bundle time; dynamic access (passing
// `process.env` itself) reads as undefined in release builds.
export const appConfig = createMobileConfig({
  EXPO_PUBLIC_APP_ENV: process.env.EXPO_PUBLIC_APP_ENV,
  EXPO_PUBLIC_API_BASE_URL: process.env.EXPO_PUBLIC_API_BASE_URL,
  EXPO_PUBLIC_SMART_RETURN_ENABLED: process.env.EXPO_PUBLIC_SMART_RETURN_ENABLED,
  EXPO_PUBLIC_MUNICIPAL_DISCOVERY_ENABLED: process.env.EXPO_PUBLIC_MUNICIPAL_DISCOVERY_ENABLED,
});
