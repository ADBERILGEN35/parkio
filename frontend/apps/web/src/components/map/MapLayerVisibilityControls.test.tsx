import { fireEvent, screen } from '@testing-library/react';
import { axe } from 'jest-axe';
import { describe, expect, it, vi } from 'vitest';
import { MapLayerVisibilityControls } from './MapLayerVisibilityControls';
import { renderWithProviders } from '@/test/utils';

describe('MapLayerVisibilityControls', () => {
  it('exposes descriptive pressed-state labels for both layer toggles', () => {
    renderWithProviders(
      <MapLayerVisibilityControls
        communityVisible
        municipalVisible={false}
        onCommunityVisibleChange={() => undefined}
        onMunicipalVisibleChange={() => undefined}
      />,
    );

    expect(
      screen.getByRole('button', {
        name: 'Community spots. Visible on map and in results',
      }),
    ).toHaveAttribute('aria-pressed', 'true');
    expect(
      screen.getByRole('button', {
        name: 'Municipal facilities. Hidden from map and results',
      }),
    ).toHaveAttribute('aria-pressed', 'false');
  });

  it('toggles both layer controls with keyboard activation', () => {
    const onCommunityVisibleChange = vi.fn();
    const onMunicipalVisibleChange = vi.fn();

    renderWithProviders(
      <MapLayerVisibilityControls
        communityVisible
        municipalVisible
        onCommunityVisibleChange={onCommunityVisibleChange}
        onMunicipalVisibleChange={onMunicipalVisibleChange}
      />,
    );

    fireEvent.keyDown(
      screen.getByRole('button', { name: 'Community spots. Visible on map and in results' }),
      { key: 'Enter' },
    );
    fireEvent.click(
      screen.getByRole('button', { name: 'Community spots. Visible on map and in results' }),
    );
    fireEvent.keyDown(
      screen.getByRole('button', { name: 'Municipal facilities. Visible on map and in results' }),
      { key: ' ' },
    );
    fireEvent.click(
      screen.getByRole('button', { name: 'Municipal facilities. Visible on map and in results' }),
    );

    expect(onCommunityVisibleChange).toHaveBeenCalledWith(false);
    expect(onMunicipalVisibleChange).toHaveBeenCalledWith(false);
  });

  it('has no automated accessibility violations', async () => {
    const { container } = renderWithProviders(
      <MapLayerVisibilityControls
        communityVisible
        municipalVisible
        onCommunityVisibleChange={() => undefined}
        onMunicipalVisibleChange={() => undefined}
      />,
    );

    expect(await axe(container)).toHaveNoViolations();
  });
});
