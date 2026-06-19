import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ErrorBoundary, __privateErrorBoundary } from './ErrorBoundary';

const reportFrontendError = vi.fn();

vi.mock('@/observability/errorReporting', () => ({
  reportFrontendError: (...args: unknown[]) => reportFrontendError(...args),
}));

function ThrowingChild({ error }: { error: Error }) {
  throw error;
}

function mockLocation() {
  const original = window.location;
  const reload = vi.fn();
  const assign = vi.fn();

  Object.defineProperty(window, 'location', {
    configurable: true,
    value: { ...original, reload, assign },
  });

  return { reload, assign, restore: () => Object.defineProperty(window, 'location', { value: original }) };
}

describe('ErrorBoundary', () => {
  let consoleError: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    sessionStorage.clear();
    reportFrontendError.mockReset();
    consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined);
  });

  afterEach(() => {
    consoleError.mockRestore();
  });

  it('renders a friendly fallback when a child throws', () => {
    render(
      <ErrorBoundary>
        <ThrowingChild error={new Error('render failed')} />
      </ErrorBoundary>,
    );

    expect(screen.getByRole('heading', { name: /something went wrong/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /reload app/i })).toBeInTheDocument();
    expect(screen.getByText(/share this diagnostic id/i)).toBeInTheDocument();
    expect(reportFrontendError).toHaveBeenCalled();
  });

  it('reload button calls window.location.reload', async () => {
    const location = mockLocation();

    render(
      <ErrorBoundary>
        <ThrowingChild error={new Error('render failed')} />
      </ErrorBoundary>,
    );

    await userEvent.click(screen.getByRole('button', { name: /reload app/i }));

    expect(location.reload).toHaveBeenCalledTimes(1);
    location.restore();
  });

  it('attempts one reload for a lazy chunk load failure', async () => {
    const location = mockLocation();

    render(
      <ErrorBoundary>
        <ThrowingChild error={new Error('Failed to fetch dynamically imported module')} />
      </ErrorBoundary>,
    );

    await waitFor(() => expect(location.reload).toHaveBeenCalledTimes(1));
    expect(sessionStorage.getItem(__privateErrorBoundary.CHUNK_RELOAD_KEY)).toBe('1');
    location.restore();
  });

  it('shows fallback on a second chunk failure instead of reloading forever', () => {
    const location = mockLocation();
    sessionStorage.setItem(__privateErrorBoundary.CHUNK_RELOAD_KEY, '1');

    render(
      <ErrorBoundary>
        <ThrowingChild error={new Error('Importing a module script failed.')} />
      </ErrorBoundary>,
    );

    expect(location.reload).not.toHaveBeenCalled();
    expect(screen.getByRole('heading', { name: /something went wrong/i })).toBeInTheDocument();
    location.restore();
  });
});
