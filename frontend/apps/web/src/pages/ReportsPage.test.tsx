import type { ModerationReport } from '@parkio/types';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { API_BASE, server } from '@/test/server';
import { renderWithProviders, resetAuth, signInAs } from '@/test/utils';
import { ReportsPage } from './ReportsPage';

const SPOT_ID = '0b8f6c3a-0000-0000-0000-0000000000s1';
const CASE_ID = '0b8f6c3a-0000-0000-0000-0000000000c1';

const report: ModerationReport = {
  id: '0b8f6c3a-0000-0000-0000-0000000000r1',
  reporterUserId: '0b8f6c3a-0000-0000-0000-0000000000u1',
  targetType: 'PARKING_SPOT',
  targetId: SPOT_ID,
  reason: 'FAKE_PHOTO',
  description: 'Photo looks staged',
  caseId: CASE_ID,
  createdAt: '2026-06-13T10:00:00Z',
};

function useReportsHandlers(reports: ModerationReport[]) {
  server.use(
    http.get(`${API_BASE}/notifications/me`, () => HttpResponse.json([])),
    http.get(`${API_BASE}/moderation/reports/me`, () => HttpResponse.json(reports)),
  );
}

describe('ReportsPage', () => {
  beforeEach(() => {
    resetAuth();
    signInAs(['USER']);
  });

  it('renders a submitted report with its case and target link', async () => {
    useReportsHandlers([report]);
    renderWithProviders(<ReportsPage />, { initialEntries: ['/reports'] });

    expect(await screen.findByText('Fake photo')).toBeInTheDocument();
    expect(screen.getByText('Case opened')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: SPOT_ID })).toHaveAttribute(
      'href',
      `/spots/${SPOT_ID}`,
    );
  });

  it('renders the appeal form controls', async () => {
    useReportsHandlers([]);
    renderWithProviders(<ReportsPage />, { initialEntries: ['/reports'] });

    expect(await screen.findByLabelText('Case id')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Submit appeal' })).toBeInTheDocument();
    expect(await screen.findByText('No reports yet')).toBeInTheDocument();
  });
});
