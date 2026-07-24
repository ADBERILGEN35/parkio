import { screen, within } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { renderWithProviders } from '@/test/utils';
import { AdminShell } from './AdminShell';

function renderAdminShell(path = '/admin') {
  return renderWithProviders(
    <Routes>
      <Route path="/admin" element={<AdminShell />}>
        <Route index element={<p>Dashboard page</p>} />
        <Route path="users/*" element={<p>Users page</p>} />
        <Route path="security" element={<p>Security page</p>} />
        <Route path="analytics" element={<p>Analytics page</p>} />
        <Route path="audit" element={<p>Audit page</p>} />
        <Route path="system" element={<p>System page</p>} />
      </Route>
      <Route path="/admin/moderation" element={<p>Moderation page</p>} />
    </Routes>,
    {
      authRoles: ['ADMIN'],
      initialEntries: [path],
    },
  );
}

describe('AdminShell manifest-derived navigation', () => {
  it('preserves every label, destination, and ordering from the former sidebar', () => {
    renderAdminShell();

    const navigation = screen.getByRole('navigation', { name: 'Admin' });
    expect(
      within(navigation)
        .getAllByRole('link')
        .map((link) => ({
          label: link.textContent,
          destination: link.getAttribute('href'),
        })),
    ).toEqual([
      { label: 'Dashboard', destination: '/admin' },
      { label: 'Users', destination: '/admin/users' },
      { label: 'Security', destination: '/admin/security' },
      { label: 'Moderation', destination: '/admin/moderation' },
      { label: 'Analytics', destination: '/admin/analytics' },
      { label: 'Audit', destination: '/admin/audit' },
      { label: 'System', destination: '/admin/system' },
    ]);
  });

  it('preserves exact dashboard matching and nested users visibility', () => {
    renderAdminShell('/admin/users/6f9619ff-8b86-4d01-b42d-00cf4fc964ff');

    expect(screen.getByRole('link', { name: 'Dashboard' })).not.toHaveAttribute(
      'aria-current',
    );
    expect(screen.getByRole('link', { name: 'Users' })).toHaveAttribute(
      'aria-current',
      'page',
    );
    expect(screen.getByText('Users page')).toBeInTheDocument();
  });
});
