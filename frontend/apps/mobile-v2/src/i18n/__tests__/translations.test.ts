import { interpolate } from '../LocaleProvider';
import { en, tr } from '../translations';

function placeholdersOf(template: string): string[] {
  return [...template.matchAll(/\{(\w+)\}/g)].map((match) => match[1]).sort();
}

describe('translation catalog', () => {
  it('en mirrors every tr key (and no extras)', () => {
    const trKeys = Object.keys(tr).sort();
    const enKeys = Object.keys(en).sort();
    expect(enKeys).toEqual(trKeys);
  });

  it('placeholders match between locales for every key', () => {
    for (const key of Object.keys(tr) as (keyof typeof tr)[]) {
      expect({ key, placeholders: placeholdersOf(en[key]) }).toEqual({
        key,
        placeholders: placeholdersOf(tr[key]),
      });
    }
  });

  it('no empty strings', () => {
    for (const key of Object.keys(tr) as (keyof typeof tr)[]) {
      expect(tr[key].trim().length).toBeGreaterThan(0);
      expect(en[key].trim().length).toBeGreaterThan(0);
    }
  });
});

describe('interpolate', () => {
  it('replaces named params and leaves unknown tokens intact', () => {
    expect(interpolate('{time} kaldı', { time: '07:42' })).toBe('07:42 kaldı');
    expect(interpolate('Seviye {level} için günlük {limit}', { level: 3, limit: 120 })).toBe(
      'Seviye 3 için günlük 120',
    );
    expect(interpolate('missing {x}', {})).toBe('missing {x}');
  });
});
