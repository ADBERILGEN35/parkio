import { z } from 'zod';

/**
 * Typed, validated runtime config.
 *
 * All client config travels through `EXPO_PUBLIC_*` env vars, which Expo inlines
 * at build time. Local dev reads `.env.local`; `hosted-beta` and `production`
 * values are injected by the matching EAS build profile (see `eas.json`). No
 * secrets are ever placed here — only public endpoints and feature flags.
 */
export type AppEnvironment = 'development' | 'hosted-beta' | 'production';

const DEFAULT_LOCAL_API = 'http://10.0.2.2:8080/api/v1';

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

export function createMobileConfig(env: NodeJS.ProcessEnv): MobileConfig {
  const raw = rawSchema.parse({
    EXPO_PUBLIC_APP_ENV: env.EXPO_PUBLIC_APP_ENV,
    EXPO_PUBLIC_API_BASE_URL: env.EXPO_PUBLIC_API_BASE_URL,
    EXPO_PUBLIC_SMART_RETURN_ENABLED: env.EXPO_PUBLIC_SMART_RETURN_ENABLED,
  });

  const appEnv: AppEnvironment = raw.EXPO_PUBLIC_APP_ENV ?? 'development';
  const isProductionLike = appEnv === 'production' || appEnv === 'hosted-beta';

  const apiBaseUrl = raw.EXPO_PUBLIC_API_BASE_URL ?? (isProductionLike ? undefined : DEFAULT_LOCAL_API);
  if (!apiBaseUrl) {
    throw new Error(`EXPO_PUBLIC_API_BASE_URL is required when EXPO_PUBLIC_APP_ENV=${appEnv}.`);
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

export const appConfig = createMobileConfig(process.env);
