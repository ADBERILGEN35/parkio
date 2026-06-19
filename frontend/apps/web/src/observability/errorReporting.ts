import { frontendConfig } from '@/config/env';
import { useAuthStore } from '@/auth/store';

export interface FrontendErrorContext {
  componentStack?: string;
  correlationId?: string;
  traceId?: string;
  route?: string;
  userAuthState?: 'authenticated' | 'anonymous';
  [key: string]: unknown;
}

const SENSITIVE_KEY_PATTERN =
  /(access[_-]?token|refresh[_-]?token|reset[_-]?token|verification[_-]?token|password|api[_-]?key|secret|authorization)/i;
const RAW_VALUE_KEY_PATTERN = /^(form|formValues|rawFormValues|values|requestBody|body|payload)$/i;
const REDACTED = '[REDACTED]';

let initialized = false;

function sanitizeUrl(rawUrl: string): string {
  try {
    const url = new URL(rawUrl, window.location.origin);
    for (const key of Array.from(url.searchParams.keys())) {
      if (SENSITIVE_KEY_PATTERN.test(key)) {
        url.searchParams.set(key, REDACTED);
      }
    }
    return `${url.pathname}${url.search}${url.hash}`;
  } catch {
    return '[invalid-url]';
  }
}

function sanitizeValue(value: unknown): unknown {
  if (value instanceof Error) {
    return { name: value.name, message: value.message };
  }
  if (Array.isArray(value)) {
    return value.map(sanitizeValue);
  }
  if (value && typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value as Record<string, unknown>).map(([key, nested]) => [
        key,
        SENSITIVE_KEY_PATTERN.test(key) || RAW_VALUE_KEY_PATTERN.test(key)
          ? REDACTED
          : sanitizeValue(nested),
      ]),
    );
  }
  return value;
}

function buildSafeContext(context: FrontendErrorContext = {}): FrontendErrorContext {
  const state = useAuthStore.getState();
  return sanitizeValue({
    ...context,
    route: context.route ?? sanitizeUrl(window.location.href),
    userAuthState: context.userAuthState ?? (state.isAuthenticated ? 'authenticated' : 'anonymous'),
    browser: {
      userAgent: navigator.userAgent,
      language: navigator.language,
    },
  }) as FrontendErrorContext;
}

function isEnabled(): boolean {
  return frontendConfig.errorReporting.provider !== 'disabled';
}

export function initFrontendErrorReporting(): void {
  if (initialized) return;
  initialized = true;

  window.addEventListener('error', (event) => {
    reportFrontendError(event.error ?? new Error(event.message), {
      source: 'window.error',
      filename: event.filename,
      lineno: event.lineno,
      colno: event.colno,
    });
  });

  window.addEventListener('unhandledrejection', (event) => {
    reportFrontendError(event.reason instanceof Error ? event.reason : new Error('Unhandled promise rejection'), {
      source: 'window.unhandledrejection',
      reasonType: typeof event.reason,
    });
  });
}

export function reportFrontendError(error: unknown, context: FrontendErrorContext = {}): void {
  if (!isEnabled()) return;

  const normalized = error instanceof Error ? error : new Error(String(error));
  const safeContext = buildSafeContext(context);

  if (frontendConfig.errorReporting.provider === 'console') {
    console.error('[frontend-error]', normalized.name, normalized.message, safeContext);
  }
}

export function reportFrontendMessage(message: string, context: FrontendErrorContext = {}): void {
  if (!isEnabled()) return;

  const safeContext = buildSafeContext(context);
  if (frontendConfig.errorReporting.provider === 'console') {
    console.info('[frontend-message]', message, safeContext);
  }
}

export const __privateErrorReporting = {
  sanitizeUrl,
  buildSafeContext,
};
