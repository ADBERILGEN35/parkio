import { screen } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it } from 'vitest';
import { renderWithProviders, resetAuth, signInAs } from '@/test/utils';
import { ProtectedRoute } from './ProtectedRoute';
import { RoleRoute } from './RoleRoute';

/** Mirrors the app's router composition: ProtectedRoute wraps RoleRoute. */
function renderGuardedApp(initialEntry: string) {
  return renderWithProviders(
    <Routes>
      <Route path="/login" element={<div>Login page stub</div>} />
      <Route element={<ProtectedRoute />}>
        <Route path="/map" element={<div>Map page stub</div>} />
        <Route element={<RoleRoute requirePrivileged />}>
          <Route path="/moderation" element={<div>Moderation dashboard stub</div>} />
          <Route path="/analytics" element={<div>Analytics dashboard stub</div>} />
        </Route>
      </Route>
    </Routes>,
    { initialEntries: [initialEntry] },
  );
}

describe('route guards', () => {
  beforeEach(() => resetAuth());

  it('redirects unauthenticated users from a protected route to /login', () => {
    renderGuardedApp('/map');
    expect(screen.getByText('Login page stub')).toBeInTheDocument();
    expect(screen.queryByText('Map page stub')).not.toBeInTheDocument();
  });

  it.each(['/moderation', '/analytics'])(
    'blocks non-privileged users from %s',
    (path) => {
      signInAs(['USER']);
      renderGuardedApp(path);
      expect(
        screen.getByText('This area requires a moderator or admin role.'),
      ).toBeInTheDocument();
      expect(screen.queryByText(/dashboard stub/)).not.toBeInTheDocument();
    },
  );

  it('lets a MODERATOR access /moderation', () => {
    signInAs(['MODERATOR']);
    renderGuardedApp('/moderation');
    expect(screen.getByText('Moderation dashboard stub')).toBeInTheDocument();
  });

  it('lets an ADMIN access /analytics', () => {
    signInAs(['ADMIN']);
    renderGuardedApp('/analytics');
    expect(screen.getByText('Analytics dashboard stub')).toBeInTheDocument();
  });
});
