import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { renderWithProviders } from '@/test/utils';
import { NotFoundPage } from './NotFoundPage';

describe('NotFoundPage', () => {
  it('offers login for anonymous users', () => {
    renderWithProviders(<NotFoundPage />, { initialEntries: ['/missing'] });

    expect(screen.getByRole('heading', { name: /page not found/i })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /go to login/i })).toHaveAttribute('href', '/login');
  });

  it('offers the map for authenticated users', () => {
    renderWithProviders(<NotFoundPage />, {
      authRoles: ['USER'],
      initialEntries: ['/missing'],
    });

    expect(screen.getByRole('link', { name: /go to map/i })).toHaveAttribute('href', '/map');
  });
});
