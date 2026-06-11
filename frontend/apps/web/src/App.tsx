import { RouterProvider } from 'react-router-dom';
import { AuthBootstrap } from '@/auth/AuthBootstrap';
import { useAuthStore } from '@/auth/store';
import { AccountSuspendedPage } from '@/pages/AccountSuspendedPage';
import { QueryProvider } from '@/providers/QueryProvider';
import { router } from '@/router';

export function App() {
  const suspended = useAuthStore((s) => s.suspended);

  return (
    <QueryProvider>
      <AuthBootstrap />
      {suspended ? <AccountSuspendedPage /> : <RouterProvider router={router} />}
    </QueryProvider>
  );
}
