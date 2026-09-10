/**
 * Dual-inventory discovery chrome (WEB-MUNI-06).
 *
 * Pure presentation resolver for map peek summary, advanced-sheet CTA, and
 * empty-state hierarchy. Does not fetch, mutate, or fuse inventories.
 */

export type DiscoveryChromeKind =
  | 'idle'
  | 'both_hidden'
  | 'loading'
  | 'error_no_results'
  | 'has_results'
  | 'no_visible_results';

export type DiscoveryChromeCtaMode =
  | 'open_results'
  | 'both_hidden'
  | 'idle'
  | 'empty'
  | 'error';

export type DiscoverySummaryVariant =
  | 'idle'
  | 'both_hidden'
  | 'searching'
  | 'error'
  | 'community'
  | 'community_filtered'
  | 'community_empty'
  | 'municipal'
  | 'both'
  | 'municipal_filtered_empty'
  | 'no_visible_results';

export type DiscoveryCtaLabelVariant =
  | 'community'
  | 'municipal'
  | 'dual'
  | 'none';

export interface MapDiscoveryChromeInput {
  municipalDiscoveryEnabled: boolean;
  communityLayerVisible: boolean;
  municipalLayerVisible: boolean;
  /** Null until the user has run a nearby search. */
  hasSearchParams: boolean;
  communityPending: boolean;
  communityError: boolean;
  /** Visible community results after presentation filters. */
  communityVisibleCount: number;
  /** Community results before presentation filters. */
  communityTotalCount: number;
  municipalPending: boolean;
  municipalError: boolean;
  /** Visible municipal results after presentation filters. */
  municipalVisibleCount: number;
  /** Municipal results before presentation filters. */
  municipalTotalCount: number;
}

export interface MapDiscoveryChrome {
  kind: DiscoveryChromeKind;
  summaryVariant: DiscoverySummaryVariant;
  /** Visible-layer community count (0 when community layer hidden). */
  communityCount: number;
  /** Visible-layer municipal filtered count (0 when municipal layer hidden). */
  municipalCount: number;
  communityTotalCount: number;
  municipalTotalCount: number;
  municipalFilteredEmpty: boolean;
  communityEmptySubordinate: boolean;
  ctaMode: DiscoveryChromeCtaMode;
  ctaLabelVariant: DiscoveryCtaLabelVariant;
  /** Count for CTA pluralization (never a fused ambiguous “parking” total). */
  ctaCount: number;
}

function settledInventoryHasResults(args: {
  layerVisible: boolean;
  pending: boolean;
  error: boolean;
  visibleCount: number;
}): boolean {
  return args.layerVisible && !args.pending && !args.error && args.visibleCount > 0;
}

/**
 * Resolve dual-inventory chrome from layer + query presentation inputs.
 * Flag-off path preserves community-only semantics.
 */
export function resolveMapDiscoveryChrome(
  input: MapDiscoveryChromeInput,
): MapDiscoveryChrome {
  const showCommunity = input.communityLayerVisible;
  const showMunicipal = input.municipalDiscoveryEnabled && input.municipalLayerVisible;

  const communityCount = showCommunity ? input.communityVisibleCount : 0;
  const municipalCount = showMunicipal ? input.municipalVisibleCount : 0;
  const communityTotalCount = showCommunity ? input.communityTotalCount : 0;
  const municipalTotalCount = showMunicipal ? input.municipalTotalCount : 0;

  const communityHasResults = settledInventoryHasResults({
    layerVisible: showCommunity,
    pending: input.communityPending,
    error: input.communityError,
    visibleCount: input.communityVisibleCount,
  });
  const municipalHasResults = settledInventoryHasResults({
    layerVisible: showMunicipal,
    pending: input.municipalPending,
    error: input.municipalError,
    visibleCount: input.municipalVisibleCount,
  });

  const municipalFilteredEmpty =
    showMunicipal &&
    !input.municipalPending &&
    !input.municipalError &&
    input.municipalTotalCount > 0 &&
    input.municipalVisibleCount === 0;

  const communityEmptySubordinate =
    input.municipalDiscoveryEnabled &&
    showCommunity &&
    !input.communityPending &&
    !input.communityError &&
    input.communityTotalCount === 0 &&
    municipalHasResults;

  const base = {
    communityCount,
    municipalCount,
    communityTotalCount,
    municipalTotalCount,
    municipalFilteredEmpty,
    communityEmptySubordinate,
  };

  // Flag off: community-only contract (ignore municipal inputs entirely).
  if (!input.municipalDiscoveryEnabled) {
    return resolveCommunityOnlyChrome(input, base);
  }

  if (!showCommunity && !showMunicipal) {
    return {
      ...base,
      kind: 'both_hidden',
      summaryVariant: 'both_hidden',
      ctaMode: 'both_hidden',
      ctaLabelVariant: 'none',
      ctaCount: 0,
    };
  }

  if (!input.hasSearchParams) {
    return {
      ...base,
      kind: 'idle',
      summaryVariant: 'idle',
      ctaMode: 'idle',
      ctaLabelVariant: 'none',
      ctaCount: 0,
    };
  }

  if (communityHasResults || municipalHasResults) {
    return resolveHasResultsChrome({
      ...base,
      communityHasResults,
      municipalHasResults,
      showCommunity,
      showMunicipal,
      communityVisibleCount: input.communityVisibleCount,
      communityTotalCount: input.communityTotalCount,
    });
  }

  const visiblePending =
    (showCommunity && input.communityPending) || (showMunicipal && input.municipalPending);
  if (visiblePending) {
    return {
      ...base,
      kind: 'loading',
      summaryVariant: 'searching',
      ctaMode: 'idle',
      ctaLabelVariant: 'none',
      ctaCount: 0,
    };
  }

  const communityFailed = showCommunity && input.communityError;
  const municipalFailed = showMunicipal && input.municipalError;
  const communitySettledEmpty =
    showCommunity && !input.communityPending && !input.communityError && input.communityTotalCount === 0;
  const municipalSettledEmpty =
    showMunicipal &&
    !input.municipalPending &&
    !input.municipalError &&
    input.municipalTotalCount === 0;

  if (
    (communityFailed || municipalFailed) &&
    !communityHasResults &&
    !municipalHasResults &&
    (communityFailed ? !municipalHasResults : true) &&
    (municipalFailed ? !communityHasResults : true)
  ) {
    // Full failure only when every visible inventory failed (or one failed and
    // the other is absent/hidden) with no usable results.
    const onlyCommunityVisible = showCommunity && !showMunicipal;
    const onlyMunicipalVisible = showMunicipal && !showCommunity;
    const bothVisibleFailed = showCommunity && showMunicipal && communityFailed && municipalFailed;
    const communityOnlyFailed = onlyCommunityVisible && communityFailed;
    const municipalOnlyFailed = onlyMunicipalVisible && municipalFailed;
    const oneFailedOtherEmpty =
      showCommunity &&
      showMunicipal &&
      ((communityFailed && municipalSettledEmpty) || (municipalFailed && communitySettledEmpty));

    if (bothVisibleFailed || communityOnlyFailed || municipalOnlyFailed || oneFailedOtherEmpty) {
      return {
        ...base,
        kind: 'error_no_results',
        summaryVariant: 'error',
        ctaMode: 'error',
        ctaLabelVariant: 'none',
        ctaCount: 0,
      };
    }
  }

  if (municipalFilteredEmpty && !communityHasResults) {
    return {
      ...base,
      kind: 'no_visible_results',
      summaryVariant: 'municipal_filtered_empty',
      ctaMode: 'open_results',
      ctaLabelVariant: showCommunity && communityTotalCount === 0 ? 'municipal' : 'municipal',
      ctaCount: municipalTotalCount,
    };
  }

  return {
    ...base,
    kind: 'no_visible_results',
    summaryVariant: 'no_visible_results',
    ctaMode: 'empty',
    ctaLabelVariant: 'none',
    ctaCount: 0,
  };
}

function resolveCommunityOnlyChrome(
  input: MapDiscoveryChromeInput,
  base: Pick<
    MapDiscoveryChrome,
    | 'communityCount'
    | 'municipalCount'
    | 'communityTotalCount'
    | 'municipalTotalCount'
    | 'municipalFilteredEmpty'
    | 'communityEmptySubordinate'
  >,
): MapDiscoveryChrome {
  if (!input.hasSearchParams) {
    return {
      ...base,
      kind: 'idle',
      summaryVariant: 'idle',
      ctaMode: 'idle',
      ctaLabelVariant: 'none',
      ctaCount: 0,
    };
  }
  if (input.communityPending) {
    return {
      ...base,
      kind: 'loading',
      summaryVariant: 'searching',
      ctaMode: 'idle',
      ctaLabelVariant: 'none',
      ctaCount: 0,
    };
  }
  if (input.communityError) {
    return {
      ...base,
      kind: 'error_no_results',
      summaryVariant: 'error',
      ctaMode: 'error',
      ctaLabelVariant: 'none',
      ctaCount: 0,
    };
  }
  if (input.communityTotalCount === 0) {
    return {
      ...base,
      kind: 'no_visible_results',
      summaryVariant: 'community_empty',
      ctaMode: 'empty',
      ctaLabelVariant: 'none',
      ctaCount: 0,
    };
  }
  if (input.communityVisibleCount === 0) {
    // Filtered-empty community still opens the sheet (filters live there).
    return {
      ...base,
      kind: 'has_results',
      summaryVariant: 'community_filtered',
      ctaMode: 'open_results',
      ctaLabelVariant: 'community',
      ctaCount: input.communityTotalCount,
    };
  }
  return {
    ...base,
    kind: 'has_results',
    summaryVariant:
      input.communityVisibleCount !== input.communityTotalCount ? 'community_filtered' : 'community',
    ctaMode: 'open_results',
    ctaLabelVariant: 'community',
    ctaCount: input.communityVisibleCount,
  };
}

function resolveHasResultsChrome(args: {
  communityHasResults: boolean;
  municipalHasResults: boolean;
  showCommunity: boolean;
  showMunicipal: boolean;
  communityVisibleCount: number;
  communityTotalCount: number;
  communityCount: number;
  municipalCount: number;
  municipalTotalCount: number;
  municipalFilteredEmpty: boolean;
  communityEmptySubordinate: boolean;
}): MapDiscoveryChrome {
  const { communityHasResults, municipalHasResults, communityVisibleCount, communityTotalCount } =
    args;

  if (communityHasResults && municipalHasResults) {
    return {
      ...args,
      kind: 'has_results',
      summaryVariant: 'both',
      ctaMode: 'open_results',
      ctaLabelVariant: 'dual',
      // CTA count is for pluralization only; label lists inventories separately.
      ctaCount: communityVisibleCount + args.municipalCount,
    };
  }

  if (municipalHasResults) {
    return {
      ...args,
      kind: 'has_results',
      summaryVariant: 'municipal',
      ctaMode: 'open_results',
      ctaLabelVariant: 'municipal',
      ctaCount: args.municipalCount,
    };
  }

  // Community-only results (municipal hidden, empty, loading, or error).
  return {
    ...args,
    kind: 'has_results',
    summaryVariant:
      communityVisibleCount !== communityTotalCount ? 'community_filtered' : 'community',
    ctaMode: 'open_results',
    ctaLabelVariant: 'community',
    ctaCount: communityVisibleCount > 0 ? communityVisibleCount : communityTotalCount,
  };
}

/** Format peek/summary copy from a resolved chrome state. */
export function formatDiscoveryChromeSummary(
  t: (key: string, options?: Record<string, unknown>) => string,
  chrome: MapDiscoveryChrome,
): string {
  switch (chrome.summaryVariant) {
    case 'idle':
      return t('sheet.summaryIdle');
    case 'both_hidden':
      return t('layers.bothHiddenTitle');
    case 'searching':
      return t('sheet.summarySearching');
    case 'error':
      return t('sheet.summaryError');
    case 'municipal':
      return t('sheet.summaryMunicipal', { count: chrome.municipalCount });
    case 'both':
      return t('sheet.summaryBoth', {
        community: chrome.communityCount,
        municipal: chrome.municipalCount,
      });
    case 'community_filtered':
      return t('sheet.summaryOf', {
        visible: chrome.communityCount,
        total: chrome.communityTotalCount,
      });
    case 'community':
      return t('sheet.summaryNearby', { count: chrome.communityCount });
    case 'community_empty':
      return t('sheet.summaryEmpty');
    case 'municipal_filtered_empty':
      return t('sheet.summaryMunicipalFilteredEmpty');
    case 'no_visible_results':
      return t('sheet.summaryNoVisibleResults');
    default:
      return t('sheet.summaryIdle');
  }
}

/** Format advanced-sheet CTA label when mode is open_results. */
export function formatDiscoveryChromeCtaLabel(
  t: (key: string, options?: Record<string, unknown>) => string,
  chrome: MapDiscoveryChrome,
): string {
  switch (chrome.ctaLabelVariant) {
    case 'municipal':
      return t('sheet.showMunicipalResultsAndFilters', { count: chrome.ctaCount });
    case 'dual':
      return t('sheet.showDualResultsAndFilters', {
        community: chrome.communityCount,
        municipal: chrome.municipalCount,
      });
    case 'community':
    default:
      return t('sheet.showResultsAndFilters', { count: chrome.ctaCount });
  }
}
