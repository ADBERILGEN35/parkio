import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { renderWithProviders } from '@/test/utils';
import { AuthSplitLayout } from './AuthSplitLayout';

describe('AuthSplitLayout', () => {
  it('draws its hero from the bundle and points at no other origin (CL-F39.2)', async () => {
    const { container } = renderWithProviders(
      <AuthSplitLayout title="Sign in" subtitle="Welcome back">
        <p>Form</p>
      </AuthSplitLayout>,
    );

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
    // Only same-origin images remain (the brand mark); the hero is drawn, not fetched.
    const sources = [...container.querySelectorAll('img')].map((img) => img.getAttribute('src') ?? '');
    expect(sources.every((src) => src.startsWith('/') && !src.startsWith('//'))).toBe(true);
    const hero = container.querySelector('aside svg');
    expect(hero).not.toBeNull();
    expect(hero).toHaveAttribute('aria-hidden', 'true');
    expect(container.innerHTML).not.toMatch(/(?:src|href|srcset)="https?:|url\(\s*['"]?https?:/i);
    expect(container.innerHTML).not.toContain('unsplash');
  });
});
