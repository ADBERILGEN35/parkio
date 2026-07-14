import { screen } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it } from 'vitest';
import { renderWithProviders, resetAuth, signInAs } from '@/test/utils';
import { ProtectedRoute } from '@/auth/ProtectedRoute';
import { RoleRoute } from '@/auth/RoleRoute';

function renderAdminGuard(initialEntry: string) {
  return renderWithProviders(
    <Routes>
      <Route path="/login" element={<div>Login page stub</div>} />
      <Route element={<ProtectedRoute />}>
        <Route element={<RoleRoute requireAdmin />}>
          <Route path="/admin" element={<div>Admin dashboard stub</div>} />
        </Route>
      </Route>
    </Routes>,
    { initialEntries: [initialEntry] },
  );
}

describe('admin route guards', () => {
  beforeEach(() => resetAuth());

  it('blocks ordinary users from /admin', () => {
    signInAs(['USER']);
    renderAdminGuard('/admin');
    expect(screen.getByText('This area requires an admin role.')).toBeInTheDocument();
    expect(screen.queryByText('Admin dashboard stub')).not.toBeInTheDocument();
  });

  it('lets ADMIN access /admin', () => {
    signInAs(['ADMIN']);
    renderAdminGuard('/admin');
    expect(screen.getByText('Admin dashboard stub')).toBeInTheDocument();
  });

  it('lets SUPER_ADMIN access /admin', () => {
    signInAs(['SUPER_ADMIN']);
    renderAdminGuard('/admin');
    expect(screen.getByText('Admin dashboard stub')).toBeInTheDocument();
  });
});
