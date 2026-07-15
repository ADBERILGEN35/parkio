import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { BrandMark } from './BrandMark';

describe('BrandMark', () => {
  it('renders the official mark with empty alt beside wordmarks', () => {
    const { container } = render(<BrandMark size={28} />);
    const img = container.querySelector('img');

    expect(img).toBeTruthy();
    expect(img?.getAttribute('src')).toBe('/brand/parkio-logo-mark.png');
    expect(img?.getAttribute('alt')).toBe('');
    expect(img?.getAttribute('width')).toBe('28');
    expect(img?.getAttribute('height')).toBe('28');
  });
});
