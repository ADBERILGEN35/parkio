import '@testing-library/jest-dom/vitest';
import { toHaveNoViolations } from 'jest-axe';
import { afterAll, afterEach, beforeAll, expect } from 'vitest';
import i18n, { initI18n } from '@/i18n';
import { server } from './server';

expect.extend(toHaveNoViolations);

/**
 * Node 24 undici rejects jsdom's AbortSignal in `new Request(..., { signal })`.
 * React Router's data router always passes a signal into createClientSideRequest.
 * Dropping the signal keeps navigation/tests working; production browsers are unaffected.
 * @see https://github.com/vitest-dev/vitest/issues/8374
 */
const OriginalRequest = globalThis.Request;
globalThis.Request = class Request extends OriginalRequest {
  constructor(input: RequestInfo | URL, init?: RequestInit) {
    if (init?.signal) {
      const rest: RequestInit = { ...init };
      delete rest.signal;
      super(input, rest);
      return;
    }
    super(input, init);
  }
} as typeof OriginalRequest;

beforeAll(async () => {
  // Keep English strings in assertions (readable); app default remains Turkish.
  await initI18n('en');
  server.listen({ onUnhandledRequest: 'error' });
});

afterEach(async () => {
  server.resetHandlers();
  localStorage.clear();
  // PreferencesCard / setLocale can switch the shared singleton mid-suite.
  if (i18n.language !== 'en') {
    await i18n.changeLanguage('en');
  }
});

afterAll(() => server.close());
