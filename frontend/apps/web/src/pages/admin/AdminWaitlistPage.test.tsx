import { screen, waitFor } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithProviders, withLocale } from '@/test/utils';
import { AdminWaitlistPage } from './AdminWaitlistPage';

const adminList = vi.fn();
const adminSummary = vi.fn();

vi.mock('@/app/AppRuntimeContext', async () => {
  const actual = await vi.importActual<typeof import('@/app/AppRuntimeContext')>(
    '@/app/AppRuntimeContext',
  );
  return {
    ...actual,
    useParkioSdk: () => ({
      waitlistApi: {
        adminSummary,
        adminList,
        exportConfirmedCsv: vi.fn(),
      },
    }),
  };
});

function renderPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/admin/waitlist" element={<AdminWaitlistPage />} />
    </Routes>,
    { authRoles: ['ADMIN'], initialEntries: ['/admin/waitlist'] },
  );
}

describe('AdminWaitlistPage full name column', () => {
  beforeEach(() => {
    adminSummary.mockResolvedValue({ pending: 0, confirmed: 1, withdrawn: 0, total: 1 });
    adminList.mockResolvedValue({
      content: [
        {
          id: '11111111-1111-1111-1111-111111111111',
          email: 'legacy@example.test',
          fullName: null,
          status: 'CONFIRMED',
          locale: 'tr',
          source: 'parkio.dev-landing',
          createdAt: '2026-09-22T10:00:00Z',
          confirmedAt: '2026-09-22T11:00:00Z',
          withdrawnAt: null,
        },
        {
          id: '22222222-2222-2222-2222-222222222222',
          email: 'named@example.test',
          fullName: 'Ayşe Yılmaz',
          status: 'PENDING',
          locale: 'tr',
          source: 'parkio.dev-landing',
          createdAt: '2026-09-22T12:00:00Z',
          confirmedAt: null,
          withdrawnAt: null,
        },
      ],
      page: 0,
      size: 20,
      totalElements: 2,
      totalPages: 1,
    });
  });

  it('shows EN missing-name label for null fullName', async () => {
    await withLocale('en');
    renderPage();
    expect(await screen.findByText('Name not provided')).toBeInTheDocument();
    expect(screen.getByText('Ayşe Yılmaz')).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: 'Full name' })).toBeInTheDocument();
  });

  it('shows TR missing-name label for null fullName', async () => {
    await withLocale('tr');
    renderPage();
    expect(await screen.findByText('Ad belirtilmemiş')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('columnheader', { name: 'Ad soyad' })).toBeInTheDocument());
  });
});