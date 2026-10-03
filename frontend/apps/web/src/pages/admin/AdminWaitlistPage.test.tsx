import { fireEvent, screen, waitFor } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithProviders, withLocale } from '@/test/utils';
import { AdminWaitlistPage } from './AdminWaitlistPage';

const adminList = vi.fn();
const adminSummary = vi.fn();
const exportConfirmedCsv = vi.fn();

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
        exportConfirmedCsv,
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

describe('AdminWaitlistPage confirmed export', () => {
  beforeEach(() => {
    adminSummary.mockResolvedValue({ pending: 0, confirmed: 0, withdrawn: 0, total: 0 });
    adminList.mockResolvedValue({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
    exportConfirmedCsv.mockReset();
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:export'), revokeObjectURL: vi.fn() }));
    // The download anchor would navigate, which jsdom does not implement.
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
  });

  it('exports by confirmation date and says when the export was truncated', async () => {
    exportConfirmedCsv.mockResolvedValue({ csv: 'email\n', truncated: true, matchingRows: 61234, rowLimit: 50000 });
    await withLocale('en');
    renderPage();

    fireEvent.change(await screen.findByLabelText('Confirmed from'), { target: { value: '2026-09-01T00:00' } });
    fireEvent.click(screen.getByRole('button', { name: 'Export confirmed CSV' }));

    expect(
      await screen.findByText(/Only the first 50000 of 61234 confirmed subscriptions were exported\./),
    ).toHaveAttribute('role', 'status');
    expect(exportConfirmedCsv).toHaveBeenCalledWith({
      confirmedFrom: new Date('2026-09-01T00:00').toISOString(),
      confirmedTo: undefined,
    });
  });

  it('shows no truncation notice for a complete export', async () => {
    exportConfirmedCsv.mockResolvedValue({ csv: 'email\n', truncated: false, matchingRows: 3, rowLimit: 50000 });
    await withLocale('en');
    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: 'Export confirmed CSV' }));

    await waitFor(() => expect(exportConfirmedCsv).toHaveBeenCalled());
    expect(screen.queryByText(/Only the first/)).not.toBeInTheDocument();
  });
});
