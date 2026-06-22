import type { AppNotification, PublicSpot, Spot } from '@parkio/types';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { renderWithProviders } from '@/test/utils';
import { NotificationItemCard, MarkReadButton } from './NotificationItemCard';
import { ProductCard, ProductCardButton } from './ProductCard';
import { SpotResultCard } from './SpotResultCard';

const publicSpot: PublicSpot = {
  id: '0b8f6c3a-0000-0000-0000-000000000010',
  mediaId: '0b8f6c3a-0000-0000-0000-000000000011',
  latitude: 41.01,
  longitude: 28.97,
  addressText: '12 Curb Lane',
  description: 'Shaded street spot',
  manualLocationEdited: false,
  suitableVehicleTypes: ['SEDAN'],
  parkingContext: 'STREET_PARKING',
  legalStatus: 'LEGAL',
  violationReasons: [],
  status: 'ACTIVE',
  expiresAt: '2026-06-13T12:00:00Z',
  createdAt: '2026-06-13T10:00:00Z',
  updatedAt: '2026-06-13T10:00:00Z',
};

const ownerSpot: Spot = {
  ...publicSpot,
  ownerUserId: '0b8f6c3a-0000-0000-0000-000000000020',
  confidenceScore: 80,
  verificationCount: 2,
  filledReportCount: 0,
};

const unreadNotification: AppNotification = {
  id: 'n-unread',
  type: 'POINT_EARNED',
  channel: 'IN_APP',
  title: 'You earned points',
  body: 'Your spot was verified.',
  status: 'SENT',
  createdAt: '2026-06-11T09:00:00Z',
  readAt: null,
};

const readNotification: AppNotification = {
  ...unreadNotification,
  id: 'n-read',
  title: 'Welcome to Parkio',
  status: 'READ',
  readAt: '2026-06-11T10:30:00Z',
};

describe('product card components', () => {
  it('applies selected and interactive states consistently', () => {
    render(
      <div>
        <ProductCard selected data-testid="selected-card">
          Selected
        </ProductCard>
        <ProductCardButton selected>Active row</ProductCardButton>
      </div>,
    );

    expect(screen.getByTestId('selected-card')).toHaveClass('border-primary');
    expect(screen.getByRole('button', { name: 'Active row' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
  });

  it('renders spot result cards from real public fields only by default', () => {
    renderWithProviders(<SpotResultCard spot={publicSpot} />);

    expect(screen.getByRole('link', { name: '12 Curb Lane' })).toHaveAttribute(
      'href',
      `/spots/${publicSpot.id}`,
    );
    expect(screen.getByText('Shaded street spot')).toBeInTheDocument();
    expect(screen.getByText('Sedan')).toBeInTheDocument();
    expect(screen.getByText('Street parking')).toBeInTheDocument();
    expect(screen.queryByText(/Confidence/)).not.toBeInTheDocument();
    expect(screen.queryByText(/verifications/)).not.toBeInTheDocument();
  });

  it('renders vehicle compatibility only from the saved vehicle profile and spot fit fields', () => {
    renderWithProviders(
      <div>
        <SpotResultCard spot={publicSpot} userVehicleType="SEDAN" />
        <SpotResultCard
          spot={{ ...publicSpot, id: 'suv-only', addressText: 'SUV Bay', suitableVehicleTypes: ['SUV'] }}
          userVehicleType="SEDAN"
        />
      </div>,
    );

    expect(screen.getByText('Fits your Sedan')).toBeInTheDocument();
    expect(screen.getByText('Not listed for Sedan')).toBeInTheDocument();
  });

  it('renders owner spot metrics only when requested and available', () => {
    renderWithProviders(<SpotResultCard spot={ownerSpot} showOwnerMetrics />);

    expect(screen.getByText('2 verifications')).toBeInTheDocument();
    expect(screen.getByText('Confidence 80')).toBeInTheDocument();
  });

  it('distinguishes unread and read notification rows', () => {
    render(
      <ul>
        <NotificationItemCard
          notification={unreadNotification}
          action={<MarkReadButton onClick={() => undefined} pending={false} />}
        />
        <NotificationItemCard notification={readNotification} />
      </ul>,
    );

    expect(screen.getByText('You earned points').closest('li')).toHaveClass('border-l-4');
    expect(screen.getByRole('button', { name: 'Mark as read' })).toBeEnabled();
    expect(screen.getByText('Welcome to Parkio').closest('li')).not.toHaveClass('border-l-4');
    expect(screen.getByText(/Read 6\/11\/2026/)).toBeInTheDocument();
  });
});
