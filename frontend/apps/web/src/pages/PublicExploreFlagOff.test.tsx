import { http, HttpResponse } from 'msw';
import { screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { API_BASE, server } from '@/test/server';
import { renderWithProviders } from '@/test/utils';
import { PublicExplorePage } from './PublicExplorePage';

describe('PublicExplorePage flag off', () => {
  it('renders no real product data and does not call the API', async () => {
    const apiCalled = vi.fn();
    server.use(
      http.get(`${API_BASE}/public/explore/facilities`, () => {
        apiCalled();
        return HttpResponse.json([]);
      }),
    );

    // Default test env keeps publicExplore off unless a suite opts in.
    renderWithProviders(<PublicExplorePage />, { initialEntries: ['/explore'] });

    expect(screen.getByText('Parking data is temporarily unavailable.')).toBeInTheDocument();
    expect(screen.queryByTestId('public-explore-product')).toBeInTheDocument();
    await waitFor(() => expect(apiCalled).not.toHaveBeenCalled());
  });
});
