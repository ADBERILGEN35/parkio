import { screen } from '@testing-library/react';
import { act } from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import { resetAuth, renderWithProviders, signInAs } from '@/test/utils';
import { NotFoundPage } from './NotFoundPage';

afterEach(() => {
  act(() => {
    resetAuth();
  });
});

describe('NotFoundPage', () => {
  it('offers login for anonymous users', () => {
    act(() => {
      resetAuth();
    });
    renderWithProviders(<NotFoundPage />, { initialEntries: ['/missing'] });

    expect(screen.getByRole('heading', { name: /page not found/i })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /go to login/i })).toHaveAttribute('href', '/login');
  });

  it('offers the map for authenticated users', () => {
    act(() => {
      signInAs(['USER']);
    });
    renderWithProviders(<NotFoundPage />, { initialEntries: ['/missing'] });

    expect(screen.getByRole('link', { name: /go to map/i })).toHaveAttribute('href', '/map');
  });
});
