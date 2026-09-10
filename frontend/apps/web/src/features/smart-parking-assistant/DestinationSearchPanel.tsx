import { Icon, SkeletonBlock, cn } from '@parkio/ui';
import type {
  DestinationSearchItem,
  DestinationSearchSource,
  SavedPlaceKind,
} from '@parkio/types';
import { composeDestinationSearch } from '@parkio/validation';
import {
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
  type KeyboardEvent,
} from 'react';
import { useTranslation } from 'react-i18next';
import { useAssistantDestinationSources } from '@/data/hooks/useAssistantDestinationSources';
import { AUTOCOMPLETE_MIN_CHARS, usePlaceAutocomplete } from '@/lib/usePlaceAutocomplete';

export type DestinationSearchPanelProps = {
  open: boolean;
  onClose: () => void;
  onSelect: (item: DestinationSearchItem) => void;
};

function sourceSectionKey(source: DestinationSearchSource): string {
  switch (source) {
    case 'SAVED_PLACE':
      return 'assistant.sections.saved';
    case 'FAVOURITE_DESTINATION':
      return 'assistant.sections.favourites';
    case 'RECENT_DESTINATION':
      return 'assistant.sections.recents';
    case 'GEOCODING':
      return 'assistant.sections.geocoding';
    default:
      return 'assistant.sections.geocoding';
  }
}

function kindLabelKey(kind: SavedPlaceKind | null | undefined): string | null {
  if (kind === 'HOME') return 'assistant.kinds.home';
  if (kind === 'WORK') return 'assistant.kinds.work';
  if (kind === 'CUSTOM') return 'assistant.kinds.custom';
  return null;
}

function sourceBadgeKey(item: DestinationSearchItem): string | null {
  if (item.source === 'FAVOURITE_DESTINATION' || item.alsoFavourite) {
    return 'assistant.kinds.favourite';
  }
  if (item.source === 'RECENT_DESTINATION' || item.alsoRecent) {
    return 'assistant.kinds.recent';
  }
  return null;
}

export function DestinationSearchPanel({ open, onClose, onSelect }: DestinationSearchPanelProps) {
  const { t } = useTranslation('map');
  const listboxId = useId();
  const inputRef = useRef<HTMLInputElement>(null);
  const [query, setQuery] = useState('');
  const [highlightedIndex, setHighlightedIndex] = useState(-1);

  const sources = useAssistantDestinationSources({ enabled: open });
  const autocomplete = usePlaceAutocomplete();

  useEffect(() => {
    if (!open) {
      setQuery('');
      setHighlightedIndex(-1);
      autocomplete.clear();
      return;
    }
    const timer = window.setTimeout(() => inputRef.current?.focus(), 0);
    return () => window.clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- clear only when closing
  }, [open]);

  const geocodingResults = useMemo(
    () =>
      autocomplete.results.map((r) => ({
        label: r.primary,
        latitude: r.lat,
        longitude: r.lng,
        placeIdentity: null,
        subtitle: r.secondary ?? null,
      })),
    [autocomplete.results],
  );

  const composed = useMemo(
    () =>
      composeDestinationSearch({
        query,
        savedPlaces: sources.saved.data ?? [],
        favouriteDestinations: sources.favourites.data ?? [],
        recentDestinations: sources.recents.data ?? [],
        geocodingResults,
        geocodeMinLength: AUTOCOMPLETE_MIN_CHARS,
      }),
    [
      geocodingResults,
      query,
      sources.favourites.data,
      sources.recents.data,
      sources.saved.data,
    ],
  );

  const flatItems = composed.items;

  const choose = (item: DestinationSearchItem) => {
    onSelect(item);
    setQuery('');
    autocomplete.clear();
    setHighlightedIndex(-1);
  };

  const onChange = (value: string) => {
    setQuery(value);
    setHighlightedIndex(-1);
    autocomplete.suggest(value);
  };

  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === 'Escape') {
      event.preventDefault();
      onClose();
      return;
    }
    if (event.key === 'ArrowDown') {
      if (flatItems.length === 0) return;
      event.preventDefault();
      setHighlightedIndex((i) => (i + 1) % flatItems.length);
    } else if (event.key === 'ArrowUp') {
      if (flatItems.length === 0) return;
      event.preventDefault();
      setHighlightedIndex((i) => (i <= 0 ? flatItems.length - 1 : i - 1));
    } else if (event.key === 'Enter') {
      if (highlightedIndex >= 0 && flatItems[highlightedIndex]) {
        event.preventDefault();
        choose(flatItems[highlightedIndex]);
      }
    }
  };

  if (!open) return null;

  const showGeocodeLoading =
    query.trim().length >= AUTOCOMPLETE_MIN_CHARS && autocomplete.status === 'loading';
  const showGeocodeError =
    query.trim().length >= AUTOCOMPLETE_MIN_CHARS && autocomplete.status === 'error';
  const showEmpty =
    flatItems.length === 0 &&
    !showGeocodeLoading &&
    autocomplete.status !== 'loading' &&
    (sources.saved.isSuccess || sources.favourites.isSuccess || sources.recents.isSuccess);

  return (
    <div
      className="mt-md rounded-2xl border border-outline-variant/40 bg-surface-container-lowest p-sm shadow-md"
      data-testid="assistant-destination-search"
    >
      <div className="flex items-center gap-xs">
        <label className="sr-only" htmlFor={listboxId + '-input'}>
          {t('assistant.searchLabel')}
        </label>
        <input
          ref={inputRef}
          id={listboxId + '-input'}
          type="search"
          role="combobox"
          aria-expanded="true"
          aria-controls={listboxId}
          aria-autocomplete="list"
          aria-activedescendant={
            highlightedIndex >= 0 ? `${listboxId}-option-${highlightedIndex}` : undefined
          }
          value={query}
          placeholder={t('assistant.searchPlaceholder')}
          onChange={(e) => onChange(e.target.value)}
          onKeyDown={onKeyDown}
          className="min-w-0 flex-1 rounded-xl border-0 bg-surface-container px-md py-sm text-body-md text-on-surface outline-none ring-0 placeholder:text-on-surface-variant focus-visible:ring-2 focus-visible:ring-primary"
          autoComplete="off"
        />
        <button
          type="button"
          aria-label={t('assistant.closeSearch')}
          onClick={onClose}
          className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full text-on-surface-variant hover:bg-surface-container focus:outline-none focus-visible:ring-2 focus-visible:ring-primary"
        >
          <Icon name="close" className="text-[18px] leading-none" />
        </button>
      </div>

      <div
        id={listboxId}
        role="listbox"
        aria-label={t('assistant.suggestionsAria')}
        className="mt-sm max-h-[min(50vh,22rem)] overflow-y-auto"
      >
        {composed.sections.map((section) => (
          <div key={section.group} className="mb-sm" data-testid={`assistant-section-${section.group}`}>
            <h3 className="m-0 px-sm py-xs text-label-sm font-semibold uppercase tracking-wide text-on-surface-variant">
              {t(sourceSectionKey(section.group))}
            </h3>
            <ul className="m-0 list-none p-0">
              {section.items.map((item) => {
                const flatIndex = flatItems.findIndex((x) => x.id === item.id);
                const active = flatIndex === highlightedIndex;
                const kindKey = kindLabelKey(item.savedPlaceKind);
                const badgeKey = sourceBadgeKey(item);
                return (
                  <li key={item.id} role="presentation">
                    <button
                      type="button"
                      id={`${listboxId}-option-${flatIndex}`}
                      role="option"
                      aria-selected={active}
                      data-testid="assistant-suggestion"
                      onMouseEnter={() => setHighlightedIndex(flatIndex)}
                      onClick={() => choose(item)}
                      className={cn(
                        'flex w-full flex-col items-start gap-xs rounded-xl px-sm py-sm text-left transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-primary',
                        active ? 'bg-primary/10 text-on-surface' : 'hover:bg-surface-container',
                      )}
                    >
                      <span className="flex w-full items-center gap-xs">
                        <Icon
                          name={
                            item.savedPlaceKind === 'HOME'
                              ? 'home'
                              : item.savedPlaceKind === 'WORK'
                                ? 'work'
                                : item.source === 'RECENT_DESTINATION'
                                  ? 'history'
                                  : item.source === 'FAVOURITE_DESTINATION'
                                    ? 'star'
                                    : 'place'
                          }
                          className="text-[18px] leading-none text-primary"
                        />
                        <span className="min-w-0 flex-1 truncate text-body-md font-medium">
                          {item.title}
                        </span>
                        {kindKey ? (
                          <span className="shrink-0 rounded-full bg-surface-container px-xs py-0.5 text-label-sm text-on-surface-variant">
                            {t(kindKey)}
                          </span>
                        ) : null}
                        {!kindKey && badgeKey ? (
                          <span className="shrink-0 rounded-full bg-surface-container px-xs py-0.5 text-label-sm text-on-surface-variant">
                            {t(badgeKey)}
                          </span>
                        ) : null}
                      </span>
                      {item.subtitle ? (
                        <span className="w-full truncate pl-[26px] text-label-sm text-on-surface-variant">
                          {item.subtitle}
                        </span>
                      ) : null}
                    </button>
                  </li>
                );
              })}
            </ul>
          </div>
        ))}

        {showGeocodeLoading ? (
          <div className="space-y-xs px-sm py-sm" aria-busy="true" aria-live="polite">
            <SkeletonBlock className="h-10 w-full rounded-xl" />
            <SkeletonBlock className="h-10 w-full rounded-xl" />
          </div>
        ) : null}

        {showGeocodeError ? (
          <p className="m-0 px-sm py-sm text-label-sm text-on-surface-variant" role="status">
            {t('assistant.geocodeError')}
          </p>
        ) : null}

        {showEmpty ? (
          <p className="m-0 px-sm py-sm text-label-sm text-on-surface-variant" role="status">
            {t('assistant.noSearchResults')}
          </p>
        ) : null}
      </div>
    </div>
  );
}
