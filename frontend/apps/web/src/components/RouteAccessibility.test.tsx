import { act, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { RouteAccessibility } from './RouteAccessibility';

function TestRoutes({ initialPath }: { initialPath: string }) {
  return (
    <MemoryRouter initialEntries={[initialPath]}>
      <Routes>
        <Route element={<RouteAccessibility />}>
          <Route path="/login" element={<main><h1>Welcome back</h1></main>} />
          <Route path="/map" element={<main><h1>Find parking</h1></main>} />
          <Route path="*" element={<main><h1>Test route</h1></main>} />
        </Route>
      </Routes>
    </MemoryRouter>
  );
}

describe('RouteAccessibility', () => {
  it('sets a meaningful document title', async () => {
    render(<TestRoutes initialPath="/login" />);

    await waitFor(() => expect(document.title).toBe('Parkio — Login'));
  });

  it('updates document title when the language changes', async () => {
    const i18n = (await import('@/i18n')).default;
    render(<TestRoutes initialPath="/login" />);

    await waitFor(() => expect(document.title).toBe('Parkio — Login'));
    await act(async () => {
      await i18n.changeLanguage('tr');
    });
    await waitFor(() => expect(document.title).toBe('Parkio — Giriş'));
    await act(async () => {
      await i18n.changeLanguage('en');
    });
    await waitFor(() => expect(document.title).toBe('Parkio — Login'));
  });

  it.each([
    ['/admin', 'Parkio — Admin dashboard', 'Parkio — Yönetim paneli'],
    ['/admin/users', 'Parkio — Users', 'Parkio — Kullanıcılar'],
    [
      '/admin/users/6f9619ff-8b86-4d01-b42d-00cf4fc964ff',
      'Parkio — User',
      'Parkio — Kullanıcı',
    ],
    ['/admin/security', 'Parkio — Security', 'Parkio — Güvenlik'],
    ['/admin/moderation', 'Parkio — Moderation', 'Parkio — Moderasyon'],
    ['/admin/analytics', 'Parkio — Analytics', 'Parkio — Analitik'],
    ['/admin/audit', 'Parkio — Audit trail', 'Parkio — Denetim izi'],
    ['/admin/system', 'Parkio — System', 'Parkio — Sistem'],
  ])(
    'sets the manifest-owned localized title for %s',
    async (path, englishTitle, turkishTitle) => {
      const i18n = (await import('@/i18n')).default;
      await act(async () => {
        await i18n.changeLanguage('en');
      });
      render(<TestRoutes initialPath={path} />);

      await waitFor(() => expect(document.title).toBe(englishTitle));
      await act(async () => {
        await i18n.changeLanguage('tr');
      });
      await waitFor(() => expect(document.title).toBe(turkishTitle));
      await act(async () => {
        await i18n.changeLanguage('en');
      });
    },
  );

  it('moves focus to the page heading after navigation render', async () => {
    render(<TestRoutes initialPath="/map" />);

    const heading = await screen.findByRole('heading', { name: 'Find parking' });
    await waitFor(() => expect(document.activeElement).toBe(heading));
  });
});
