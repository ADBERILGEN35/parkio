import { RouterProvider } from 'react-router-dom';
import { AppRuntimeProvider } from '@/app/AppRuntimeProvider';
import type { WebAppRuntime } from '@/app/runtime';
import { AuthBootstrap } from '@/auth/AuthBootstrap';
import { AppToaster } from '@/components/AppToaster';
import { ErrorBoundary } from '@/components/ErrorBoundary';
import { OfflineBanner } from '@/components/OfflineBanner';
import { LocaleBootstrap } from '@/i18n/LocaleBootstrap';
import { SessionQueryCacheSync } from '@/data/SessionQueryCacheSync';
import { QueryProvider } from '@/providers/QueryProvider';

function AppContent({ runtime }: { runtime: WebAppRuntime }) {
  return (
    <>
      <SessionQueryCacheSync />
      <AuthBootstrap />
      <LocaleBootstrap />
      <OfflineBanner />
      <RouterProvider router={runtime.router} />
      <AppToaster />
    </>
  );
}

export function App({ runtime }: { runtime: WebAppRuntime }) {
  return (
    <ErrorBoundary authStore={runtime.authStore}>
      <AppRuntimeProvider runtime={runtime}>
        <QueryProvider client={runtime.queryClient}>
          <AppContent runtime={runtime} />
        </QueryProvider>
      </AppRuntimeProvider>
    </ErrorBoundary>
  );
}
