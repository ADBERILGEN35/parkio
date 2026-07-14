import { RouterProvider } from 'react-router-dom';
import { AuthBootstrap } from '@/auth/AuthBootstrap';
import { useAuthStore } from '@/auth/store';
import { AppToaster } from '@/components/AppToaster';
import { ErrorBoundary } from '@/components/ErrorBoundary';
import { OfflineBanner } from '@/components/OfflineBanner';
import { LocaleBootstrap } from '@/i18n/LocaleBootstrap';
import { AccountSuspendedPage } from '@/pages/AccountSuspendedPage';
import { QueryProvider } from '@/providers/QueryProvider';
import { router } from '@/router';

export function App() {
  const suspended = useAuthStore((s) => s.suspended);

  return (
    <ErrorBoundary>
      <QueryProvider>
        <AuthBootstrap />
        <LocaleBootstrap />
        <OfflineBanner />
        {suspended ? <AccountSuspendedPage /> : <RouterProvider router={router} />}
        <AppToaster />
      </QueryProvider>
    </ErrorBoundary>
  );
}
