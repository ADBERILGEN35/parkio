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

const rawSchema = z.object({
  EXPO_PUBLIC_APP_ENV: z.enum(['development', 'hosted-beta', 'production']).optional(),
  EXPO_PUBLIC_API_BASE_URL: z.preprocess(blankToUndefined, z.string().url().optional()),
  EXPO_PUBLIC_SMART_RETURN_ENABLED: z.enum(['true', 'false']).optional(),
});

export interface MobileConfig {
  appEnv: AppEnvironment;
  isProductionLike: boolean;
  apiBaseUrl: string;
  features: {
    smartReturn: boolean;
  };
}

export function createMobileConfig(env: Record<string, string | undefined>): MobileConfig {
  const raw = rawSchema.parse({
    EXPO_PUBLIC_APP_ENV: env.EXPO_PUBLIC_APP_ENV,
    EXPO_PUBLIC_API_BASE_URL: env.EXPO_PUBLIC_API_BASE_URL,
    EXPO_PUBLIC_SMART_RETURN_ENABLED: env.EXPO_PUBLIC_SMART_RETURN_ENABLED,
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
});
