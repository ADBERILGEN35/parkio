/**
 * Crash reporting seam — captures every crash class the app can produce:
 *
 *  - fatal + non-fatal JS errors (`ErrorUtils` global handler),
 *  - unhandled promise rejections (Hermes rejection tracker when available),
 *  - render crashes (forwarded by {@link ErrorBoundary} via `recordError`).
 *
 * The sink is intentionally pluggable: in development everything goes to the
 * console; in release builds errors are kept in a small in-memory ring so a
 * vendor SDK (Firebase Crashlytics / Sentry) can drain them the moment it is
 * wired in. Crashlytics itself requires a native module + `google-services.json`
 * and therefore a Development/EAS build — see docs/beta/mobile-release.md.
 * Native (non-JS) crash capture also only exists once that SDK is present.
 */

interface RecordedError {
  message: string;
  stack?: string;
  fatal: boolean;
  context?: string;
  at: number;
}

const MAX_BUFFERED = 25;
const buffer: RecordedError[] = [];
let installed = false;

type VendorSink = (error: RecordedError) => void;
let vendorSink: VendorSink | null = null;

/** Wire a vendor SDK (Crashlytics/Sentry). Drains buffered errors immediately. */
export function setCrashSink(sink: VendorSink): void {
  vendorSink = sink;
  for (const entry of buffer.splice(0)) sink(entry);
}

export function recordError(error: unknown, context?: string, fatal = false): void {
  const normalized: RecordedError = {
    message: error instanceof Error ? error.message : String(error),
    stack: error instanceof Error ? error.stack : undefined,
    fatal,
    context,
    at: Date.now(),
  };
  if (__DEV__) {
    console.error(`[crash:${fatal ? 'fatal' : 'non-fatal'}]${context ? ` ${context}` : ''}`, error);
    return;
  }
  if (vendorSink) {
    vendorSink(normalized);
    return;
  }
  buffer.push(normalized);
  if (buffer.length > MAX_BUFFERED) buffer.shift();
}

/** Errors captured before a vendor sink existed (release builds only). */
export function getBufferedErrors(): readonly RecordedError[] {
  return buffer;
}

interface HermesPromiseTracker {
  enablePromiseRejectionTracker?: (options: {
    allRejections: boolean;
    onUnhandled: (id: number, error: unknown) => void;
  }) => void;
}

/** Install the global handlers exactly once, as early as possible at startup. */
export function installCrashReporting(): void {
  if (installed) return;
  installed = true;

  const errorUtils = (globalThis as { ErrorUtils?: { getGlobalHandler(): (e: Error, f?: boolean) => void; setGlobalHandler(h: (e: Error, f?: boolean) => void): void } }).ErrorUtils;
  if (errorUtils) {
    const previous = errorUtils.getGlobalHandler();
    errorUtils.setGlobalHandler((error, isFatal) => {
      recordError(error, 'global', Boolean(isFatal));
      previous?.(error, isFatal);
    });
  }

  const hermes = (globalThis as { HermesInternal?: HermesPromiseTracker }).HermesInternal;
  hermes?.enablePromiseRejectionTracker?.({
    allRejections: true,
    onUnhandled: (_id, error) => recordError(error, 'unhandled-rejection'),
  });
}
