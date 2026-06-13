import type { AppNotification } from '@parkio/types';
import { http, HttpResponse } from 'msw';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { API_BASE, server } from '@/test/server';
import { renderWithProviders, resetAuth, signInAs } from '@/test/utils';
import { NotificationsPage } from './NotificationsPage';

function makeNotifications(): AppNotification[] {
  return [
    {
      id: 'n-unread',
      type: 'POINT_EARNED',
      channel: 'IN_APP',
      title: 'You earned points',
      body: 'Your spot was verified.',
      status: 'SENT',
      createdAt: '2026-06-11T09:00:00Z',
      readAt: null,
    },
    {
      id: 'n-read',
      type: 'SYSTEM',
      channel: 'IN_APP',
      title: 'Welcome to Parkio',
      body: 'Share a spot to get started.',
      status: 'READ',
      createdAt: '2026-06-10T09:00:00Z',
      readAt: '2026-06-10T10:00:00Z',
    },
  ];
}

/** Stateful handlers: PATCH mutates the list the next GET returns. */
function useNotificationHandlers(notifications: AppNotification[]) {
  server.use(
    http.get(`${API_BASE}/notifications/me`, () => HttpResponse.json(notifications)),
    http.patch(`${API_BASE}/notifications/:id/read`, ({ params }) => {
      const target = notifications.find((n) => n.id === params.id);
      if (!target) return HttpResponse.json(null, { status: 404 });
      target.status = 'READ';
      target.readAt = '2026-06-11T10:30:00Z';
      return HttpResponse.json(target);
    }),
  );
}

describe('NotificationsPage', () => {
  beforeEach(() => {
    resetAuth();
    signInAs(['USER']);
  });

  it('renders grouped notifications with a single mark-as-read action for the unread item', async () => {
    useNotificationHandlers(makeNotifications());
    renderWithProviders(<NotificationsPage />, { initialEntries: ['/notifications'] });

    expect(await screen.findByText('You earned points')).toBeInTheDocument();
    expect(screen.getByText('Welcome to Parkio')).toBeInTheDocument();
    // NEW / EARLIER group headers and the filter chips are present.
    expect(screen.getByText('New')).toBeInTheDocument();
    expect(screen.getByText('Earlier')).toBeInTheDocument();
    expect(screen.getByText('All activity')).toBeInTheDocument();
    // Only the one unread item exposes a mark-as-read action.
    expect(screen.getAllByRole('button', { name: 'Mark as read' })).toHaveLength(1);
  });

  it('filters to unread-only via the Unread chip (frontend-only)', async () => {
    useNotificationHandlers(makeNotifications());
    renderWithProviders(<NotificationsPage />, { initialEntries: ['/notifications'] });
    const user = userEvent.setup();

    expect(await screen.findByText('Welcome to Parkio')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Unread/ }));

    // The read notification is hidden; the unread one remains.
    expect(screen.queryByText('Welcome to Parkio')).not.toBeInTheDocument();
    expect(screen.getByText('You earned points')).toBeInTheDocument();
  });

  it('shows the empty state when there are no notifications', async () => {
    server.use(http.get(`${API_BASE}/notifications/me`, () => HttpResponse.json([])));
    renderWithProviders(<NotificationsPage />, { initialEntries: ['/notifications'] });

    expect(await screen.findByText(/No notifications yet/)).toBeInTheDocument();
  });

  it('marks a notification as read and refreshes the list', async () => {
    useNotificationHandlers(makeNotifications());
    renderWithProviders(<NotificationsPage />, { initialEntries: ['/notifications'] });
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'Mark as read' }));

    // After PATCH + ['notifications'] invalidation the refetched list is all-read,
    // so no item exposes a mark-as-read action any more.
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: 'Mark as read' })).not.toBeInTheDocument(),
    );
    expect(screen.getByText('You earned points')).toBeInTheDocument();
    expect(screen.getByText('Welcome to Parkio')).toBeInTheDocument();
  });
});
