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

  it('renders the notification list with read/unread badges', async () => {
    useNotificationHandlers(makeNotifications());
    renderWithProviders(<NotificationsPage />, { initialEntries: ['/notifications'] });

    expect(await screen.findByText('You earned points')).toBeInTheDocument();
    expect(screen.getByText('Welcome to Parkio')).toBeInTheDocument();
    expect(screen.getAllByText('Unread')).toHaveLength(1);
    expect(screen.getAllByText('Read')).toHaveLength(1);
  });

  it('shows the empty state when there are no notifications', async () => {
    server.use(http.get(`${API_BASE}/notifications/me`, () => HttpResponse.json([])));
    renderWithProviders(<NotificationsPage />, { initialEntries: ['/notifications'] });

    expect(
      await screen.findByText(/No notifications yet/),
    ).toBeInTheDocument();
  });

  it('marks a notification as read and refreshes the list', async () => {
    useNotificationHandlers(makeNotifications());
    renderWithProviders(<NotificationsPage />, { initialEntries: ['/notifications'] });
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'Mark as read' }));

    // After PATCH + ['notifications'] invalidation the refetched list is all-read.
    await waitFor(() => expect(screen.queryByText('Unread')).not.toBeInTheDocument());
    expect(screen.getAllByText('Read')).toHaveLength(2);
    expect(screen.queryByRole('button', { name: 'Mark as read' })).not.toBeInTheDocument();
  });
});
