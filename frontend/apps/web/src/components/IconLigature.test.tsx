import { Icon } from '@parkio/ui';
import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

const TOKEN_LEAK_RE = /(?:\+)?__[A-Z0-9]+(?:__[A-Z0-9]+)*__/;

describe('Icon ligature integrity', () => {
  it('keeps material symbol names lowercase inside uppercase parents', () => {
    const { container } = render(
      <p className="uppercase tracking-wider">
        <Icon name="add_location_alt" data-testid="icon" />
        Park yeri paylaş
      </p>,
    );

    const icon = container.querySelector('[data-testid="icon"]');
    expect(icon).not.toBeNull();
    expect(icon?.textContent).toBe('add_location_alt');
    expect(icon?.textContent).not.toMatch(/ADD_LOCATION_ALT|SETTINGS|PERSON_PIN/);
    expect(TOKEN_LEAK_RE.test(container.textContent ?? '')).toBe(false);

    const style = getComputedStyle(icon as Element);
    expect(style.textTransform === 'none' || style.textTransform === '').toBe(true);
  });

  it('normalizes mixed-case icon names to lowercase ligatures', () => {
    const { container } = render(<Icon name="Person_Pin_Circle" data-testid="pin" />);
    expect(container.querySelector('[data-testid="pin"]')?.textContent).toBe('person_pin_circle');
  });
});
