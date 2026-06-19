import { Component, type ErrorInfo, type ReactNode } from 'react';
import { Button, ErrorMessage, LoadingState } from '@parkio/ui';
import { useAuthStore } from '@/auth/store';
import { frontendConfig } from '@/config/env';
import { reportFrontendError } from '@/observability/errorReporting';

const CHUNK_RELOAD_KEY = 'parkio.chunk-reload-attempted';
const CHUNK_ERROR_PATTERNS = [
  /failed to fetch dynamically imported module/i,
  /loading chunk \d+ failed/i,
  /loading css chunk \d+ failed/i,
  /importing a module script failed/i,
];

interface ErrorBoundaryProps {
  children: ReactNode;
}

interface ErrorBoundaryState {
  error: Error | null;
  traceId: string | null;
  chunkReloading: boolean;
}

function toError(error: unknown): Error {
  return error instanceof Error ? error : new Error(String(error));
}

export function isChunkLoadError(error: unknown): boolean {
  const normalized = toError(error);
  const text = `${normalized.name} ${normalized.message}`;
  return CHUNK_ERROR_PATTERNS.some((pattern) => pattern.test(text));
}

function readAuthDestination(): '/map' | '/login' {
  return useAuthStore.getState().isAuthenticated ? '/map' : '/login';
}

export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  state: ErrorBoundaryState = { error: null, traceId: null, chunkReloading: false };

  static getDerivedStateFromError(error: unknown): Partial<ErrorBoundaryState> {
    return {
      error: toError(error),
      traceId: crypto.randomUUID(),
    };
  }

  componentDidCatch(error: unknown, info: ErrorInfo): void {
    const traceId = this.state.traceId ?? crypto.randomUUID();
    const chunkError = isChunkLoadError(error);

    reportFrontendError(error, {
      source: 'react.error-boundary',
      componentStack: info.componentStack ?? undefined,
      traceId,
      chunkError,
    });

    if (chunkError && sessionStorage.getItem(CHUNK_RELOAD_KEY) !== '1') {
      sessionStorage.setItem(CHUNK_RELOAD_KEY, '1');
      this.setState({ chunkReloading: true });
      window.location.reload();
    }
  }

  handleReload = (): void => {
    window.location.reload();
  };

  handleNavigate = (): void => {
    window.location.assign(readAuthDestination());
  };

  render(): ReactNode {
    if (!this.state.error) {
      return this.props.children;
    }

    if (this.state.chunkReloading) {
      return (
        <main className="flex min-h-screen items-center justify-center bg-background px-md text-on-background">
          <LoadingState label="Reloading app…" />
        </main>
      );
    }

    const destination = readAuthDestination();
    return (
      <main className="min-h-screen bg-background px-md py-xl text-on-background">
        <section className="mx-auto flex min-h-[70vh] max-w-xl flex-col justify-center">
          <p className="m-0 text-label-lg font-semibold uppercase tracking-[0.08em] text-primary">
            Parkio
          </p>
          <h1 className="m-0 mt-sm text-display-md-mobile text-on-surface md:text-display-md">
            Something went wrong
          </h1>
          <p className="m-0 mt-md text-body-lg text-on-surface-variant">
            We could not keep the app running safely. Reload the app, or return to a stable
            starting point and try again.
          </p>
          <div className="mt-lg flex flex-wrap gap-sm">
            <Button onClick={this.handleReload}>Reload app</Button>
            <Button variant="outline" onClick={this.handleNavigate}>
              Go to {destination === '/map' ? 'map' : 'login'}
            </Button>
          </div>
          {this.state.traceId ? (
            <div className="mt-lg">
              <ErrorMessage
                message="Share this diagnostic id if you contact support."
                traceId={this.state.traceId}
              />
            </div>
          ) : null}
          {!frontendConfig.isProductionLike ? (
            <pre className="mt-lg max-h-56 overflow-auto rounded-lg bg-surface-container p-md text-body-sm text-on-surface">
              {this.state.error.stack ?? this.state.error.message}
            </pre>
          ) : null}
        </section>
      </main>
    );
  }
}

export const __privateErrorBoundary = {
  CHUNK_RELOAD_KEY,
};
