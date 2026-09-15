import { describe, expect, it } from 'vitest';
import {
  formatDiscoveryChromeCtaLabel,
  formatDiscoveryChromeSummary,
  resolveMapDiscoveryChrome,
  type MapDiscoveryChromeInput,
} from './mapDiscoveryChrome';

function base(overrides: Partial<MapDiscoveryChromeInput> = {}): MapDiscoveryChromeInput {
  return {
    municipalDiscoveryEnabled: true,
    communityLayerVisible: true,
    municipalLayerVisible: true,
    hasSearchParams: true,
    communityPending: false,
    communityError: false,
    communityVisibleCount: 0,
    communityTotalCount: 0,
    municipalPending: false,
    municipalError: false,
    municipalVisibleCount: 0,
    municipalTotalCount: 0,
    ...overrides,
  };
}

const t = (key: string, options?: Record<string, unknown>) => {
  if (!options) return key;
  return `${key}:${JSON.stringify(options)}`;
};

describe('resolveMapDiscoveryChrome', () => {
  it('1. community 0, municipal >0 → municipal summary, open CTA', () => {
    const chrome = resolveMapDiscoveryChrome(
      base({ municipalVisibleCount: 18, municipalTotalCount: 18 }),
    );
    expect(chrome.kind).toBe('has_results');
    expect(chrome.summaryVariant).toBe('municipal');
    expect(chrome.municipalCount).toBe(18);
    expect(chrome.communityCount).toBe(0);
    expect(chrome.ctaMode).toBe('open_results');
    expect(chrome.ctaLabelVariant).toBe('municipal');
    expect(chrome.communityEmptySubordinate).toBe(true);
    expect(formatDiscoveryChromeSummary(t, chrome)).toContain('summaryMunicipal');
    expect(formatDiscoveryChromeSummary(t, chrome)).not.toContain('summaryEmpty');
  });

  it('2. community >0, municipal 0 → community summary', () => {
    const chrome = resolveMapDiscoveryChrome(
      base({ communityVisibleCount: 3, communityTotalCount: 3 }),
    );
    expect(chrome.summaryVariant).toBe('community');
    expect(chrome.ctaLabelVariant).toBe('community');
    expect(chrome.municipalCount).toBe(0);
  });

  it('3. both >0 → dual summary without fused total field', () => {
    const chrome = resolveMapDiscoveryChrome(
      base({
        communityVisibleCount: 3,
        communityTotalCount: 3,
        municipalVisibleCount: 18,
        municipalTotalCount: 18,
      }),
    );
    expect(chrome.summaryVariant).toBe('both');
    expect(chrome.ctaLabelVariant).toBe('dual');
    const summary = formatDiscoveryChromeSummary(t, chrome);
    expect(summary).toContain('"community":3');
    expect(summary).toContain('"municipal":18');
    expect(summary).not.toMatch(/"count":21/);
  });

  it('4. both 0 → true no-visible-results (not both-hidden)', () => {
    const chrome = resolveMapDiscoveryChrome(base());
    expect(chrome.kind).toBe('no_visible_results');
    expect(chrome.summaryVariant).toBe('no_visible_results');
    expect(formatDiscoveryChromeSummary(t, chrome)).toContain('summaryNoVisibleResults');
  });

  it('5. both hidden', () => {
    const chrome = resolveMapDiscoveryChrome(
      base({
        communityLayerVisible: false,
        municipalLayerVisible: false,
        municipalVisibleCount: 5,
        municipalTotalCount: 5,
        communityVisibleCount: 2,
        communityTotalCount: 2,
      }),
    );
    expect(chrome.kind).toBe('both_hidden');
    expect(chrome.communityCount).toBe(0);
    expect(chrome.municipalCount).toBe(0);
    expect(chrome.ctaMode).toBe('both_hidden');
  });

  it('6. community hidden, municipal >0', () => {
    const chrome = resolveMapDiscoveryChrome(
      base({
        communityLayerVisible: false,
        communityVisibleCount: 4,
        communityTotalCount: 4,
        municipalVisibleCount: 7,
        municipalTotalCount: 7,
      }),
    );
    expect(chrome.summaryVariant).toBe('municipal');
    expect(chrome.communityCount).toBe(0);
    expect(chrome.municipalCount).toBe(7);
  });

  it('7. municipal hidden, community >0', () => {
    const chrome = resolveMapDiscoveryChrome(
      base({
        municipalLayerVisible: false,
        communityVisibleCount: 4,
        communityTotalCount: 4,
        municipalVisibleCount: 7,
        municipalTotalCount: 7,
      }),
    );
    expect(chrome.summaryVariant).toBe('community');
    expect(chrome.municipalCount).toBe(0);
  });

  it('8. municipal filtered to 0 with unfiltered >0', () => {
    const chrome = resolveMapDiscoveryChrome(
      base({ municipalVisibleCount: 0, municipalTotalCount: 12 }),
    );
    expect(chrome.municipalFilteredEmpty).toBe(true);
    expect(chrome.summaryVariant).toBe('municipal_filtered_empty');
    expect(chrome.ctaMode).toBe('open_results');
    expect(formatDiscoveryChromeSummary(t, chrome)).toContain('summaryMunicipalFilteredEmpty');
  });

  it('9. community error, municipal >0 → municipal results win chrome', () => {
    const chrome = resolveMapDiscoveryChrome(
      base({
        communityError: true,
        municipalVisibleCount: 5,
        municipalTotalCount: 5,
      }),
    );
    expect(chrome.kind).toBe('has_results');
    expect(chrome.summaryVariant).toBe('municipal');
  });

  it('10. municipal error, community >0 → community results win chrome', () => {
    const chrome = resolveMapDiscoveryChrome(
      base({
        municipalError: true,
        communityVisibleCount: 2,
        communityTotalCount: 2,
      }),
    );
    expect(chrome.kind).toBe('has_results');
    expect(chrome.summaryVariant).toBe('community');
  });

  it('11. both error, no results', () => {
    const chrome = resolveMapDiscoveryChrome(
      base({ communityError: true, municipalError: true }),
    );
    expect(chrome.kind).toBe('error_no_results');
    expect(chrome.summaryVariant).toBe('error');
  });

  it('12. community loading, municipal >0 → has municipal results', () => {
    const chrome = resolveMapDiscoveryChrome(
      base({
        communityPending: true,
        municipalVisibleCount: 4,
        municipalTotalCount: 4,
      }),
    );
    expect(chrome.kind).toBe('has_results');
    expect(chrome.summaryVariant).toBe('municipal');
  });

  it('13. municipal loading, community >0 → has community results', () => {
    const chrome = resolveMapDiscoveryChrome(
      base({
        municipalPending: true,
        communityVisibleCount: 2,
        communityTotalCount: 2,
      }),
    );
    expect(chrome.kind).toBe('has_results');
    expect(chrome.summaryVariant).toBe('community');
  });

  it('14. both loading', () => {
    const chrome = resolveMapDiscoveryChrome(
      base({ communityPending: true, municipalPending: true }),
    );
    expect(chrome.kind).toBe('loading');
    expect(chrome.summaryVariant).toBe('searching');
  });

  it('15. flag off preserves community-only empty/summary keys', () => {
    const empty = resolveMapDiscoveryChrome(
      base({
        municipalDiscoveryEnabled: false,
        municipalVisibleCount: 99,
        municipalTotalCount: 99,
      }),
    );
    expect(empty.summaryVariant).toBe('community_empty');
    expect(formatDiscoveryChromeSummary(t, empty)).toContain('summaryEmpty');

    const withSpots = resolveMapDiscoveryChrome(
      base({
        municipalDiscoveryEnabled: false,
        communityVisibleCount: 2,
        communityTotalCount: 2,
        municipalVisibleCount: 99,
        municipalTotalCount: 99,
      }),
    );
    expect(withSpots.summaryVariant).toBe('community');
    expect(withSpots.ctaLabelVariant).toBe('community');
  });

  it('16. hidden inventory does not affect visible counts', () => {
    const chrome = resolveMapDiscoveryChrome(
      base({
        communityLayerVisible: false,
        municipalLayerVisible: true,
        communityVisibleCount: 9,
        communityTotalCount: 9,
        municipalVisibleCount: 2,
        municipalTotalCount: 2,
      }),
    );
    expect(chrome.communityCount).toBe(0);
    expect(chrome.municipalCount).toBe(2);
  });

  it('17. count formatting / pluralization params are deterministic', () => {
    const chrome = resolveMapDiscoveryChrome(
      base({ municipalVisibleCount: 1, municipalTotalCount: 1 }),
    );
    expect(formatDiscoveryChromeSummary(t, chrome)).toBe(
      'sheet.summaryMunicipal:{"count":1}',
    );
    expect(formatDiscoveryChromeCtaLabel(t, chrome)).toBe(
      'sheet.showMunicipalResultsAndFilters:{"count":1}',
    );
  });

  it('18. no fused ambiguous total returned on dual CTA label', () => {
    const chrome = resolveMapDiscoveryChrome(
      base({
        communityVisibleCount: 3,
        communityTotalCount: 3,
        municipalVisibleCount: 18,
        municipalTotalCount: 18,
      }),
    );
    const label = formatDiscoveryChromeCtaLabel(t, chrome);
    expect(label).toContain('showDualResultsAndFilters');
    expect(label).toContain('"community":3');
    expect(label).toContain('"municipal":18');
  });

  it('idle without search params', () => {
    const chrome = resolveMapDiscoveryChrome(base({ hasSearchParams: false }));
    expect(chrome.kind).toBe('idle');
    expect(chrome.ctaMode).toBe('idle');
  });

  it('municipal filtered empty with community results still reports community', () => {
    const chrome = resolveMapDiscoveryChrome(
      base({
        communityVisibleCount: 2,
        communityTotalCount: 2,
        municipalVisibleCount: 0,
        municipalTotalCount: 10,
      }),
    );
    expect(chrome.kind).toBe('has_results');
    expect(chrome.summaryVariant).toBe('community');
    expect(chrome.municipalFilteredEmpty).toBe(true);
  });
});
