import type { ModerationAppeal, ModerationCase } from '@parkio/types';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { API_BASE, server } from '@/test/server';
import { renderWithProviders, resetAuth, signInAs } from '@/test/utils';
import { ModerationPage } from './ModerationPage';

const CASE_ID = '0b8f6c3a-0000-0000-0000-0000000000c1';

const openCase: ModerationCase = {
  id: CASE_ID,
  targetType: 'PARKING_SPOT',
  targetId: '0b8f6c3a-0000-0000-0000-0000000000c2',
  reason: 'FAKE_PHOTO',
  severity: 'HIGH',
  status: 'OPEN',
  assignedModeratorId: null,
  reportCount: 3,
  resolutionAction: null,
  resolutionNote: null,
  openedAt: '2026-06-11T09:00:00Z',
  updatedAt: '2026-06-11T09:00:00Z',
  resolvedAt: null,
};

const openAppeal: ModerationAppeal = {
  id: '0b8f6c3a-0000-0000-0000-0000000000a1',
  appealUserId: '0b8f6c3a-0000-0000-0000-0000000000a2',
  caseId: CASE_ID,
  note: 'Please reconsider.',
  status: 'OPEN',
  resolverModeratorId: null,
  resolutionNote: null,
  createdAt: '2026-06-11T09:30:00Z',
  resolvedAt: null,
};

function useModerationHandlers() {
  server.use(
    http.get(`${API_BASE}/notifications/me`, () => HttpResponse.json([])),
    http.get(`${API_BASE}/moderation/cases`, () => HttpResponse.json([openCase])),
    http.get(`${API_BASE}/moderation/cases/${CASE_ID}`, () => HttpResponse.json(openCase)),
    http.get(`${API_BASE}/moderation/appeals`, () => HttpResponse.json([openAppeal])),
  );
}

describe('ModerationPage', () => {
  beforeEach(() => {
    resetAuth();
    signInAs(['MODERATOR']);
  });

  it('renders the case queue with reason, severity and status', async () => {
    useModerationHandlers();
    renderWithProviders(<ModerationPage />, { initialEntries: ['/moderation'] });

    expect(await screen.findByText('Fake photo')).toBeInTheDocument();
    expect(screen.getAllByText('High').length).toBeGreaterThan(0);
  });

  it('opens the case detail with an assign action when a case is selected', async () => {
    useModerationHandlers();
    renderWithProviders(<ModerationPage />, { initialEntries: ['/moderation'] });
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: /Fake photo/ }));

    expect(await screen.findByRole('button', { name: /Assign to me/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Resolve case' })).toBeInTheDocument();
  });

  it('renders appeal resolve controls for an open appeal', async () => {
    useModerationHandlers();
    renderWithProviders(<ModerationPage />, { initialEntries: ['/moderation'] });

    expect(await screen.findByRole('button', { name: 'Resolve appeal' })).toBeInTheDocument();
    expect(screen.getByText('Accept')).toBeInTheDocument();
    expect(screen.getByText('Reject')).toBeInTheDocument();
  });
});
