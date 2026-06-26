import { Button, Icon, Input, SkeletonBlock, cn } from '@parkio/ui';
import {
  useEffect,
  useId,
  useRef,
  useState,
  type FormEvent,
  type KeyboardEvent,
} from 'react';
import { type GeocodeResult } from '@/lib/geocoding';
import { usePlaceAutocomplete, type AutocompleteStatus } from '@/lib/usePlaceAutocomplete';

export interface PlaceSearchProps {
  /** Visible field label (also the accessible name). */
  label?: string;
  placeholder?: string;
  /** Compact mobile chrome for map search pills. Keeps the same accessible name. */
  compact?: boolean;
  /**
   * Called when the user commits a place — via a suggestion (click/keyboard) or a
   * single-match submit. The resolved `GeocodeResult` carries lat/lng + labels.
   */
  onSelect: (result: GeocodeResult) => void;
}

const DEFAULT_PLACEHOLDER = 'Search street, neighborhood, or place...';

/**
 * Address/place typeahead used by both `/map` and `/upload`.
 *
 * Owns its own query text, debounced suggestions ({@link usePlaceAutocomplete}),
 * dropdown open/highlight state, keyboard navigation, and a submit fallback. It is
 * purely a *search* control: it never sets coordinates itself — the consumer reacts
 * to {@link PlaceSearchProps.onSelect} (e.g. center a map, fill lat/lng). Geocoding
 * is proxied through Parkio's backend; see `lib/geocoding.ts`.
 */
export function PlaceSearch({
  label = 'Search location',
  placeholder = DEFAULT_PLACEHOLDER,
  compact = false,
  onSelect,
}: PlaceSearchProps) {
  const listboxId = useId();
  const optionId = (index: number) => `${listboxId}-option-${index}`;

  const [query, setQuery] = useState('');
  const [dropdownOpen, setDropdownOpen] = useState(false);
  const [highlightedIndex, setHighlightedIndex] = useState(-1);
  const blurTimeoutRef = useRef<ReturnType<typeof setTimeout>>();

  const autocomplete = usePlaceAutocomplete();
  const suggestions = autocomplete.results;
  const showDropdown = dropdownOpen && autocomplete.status !== 'idle';

  const closeDropdown = () => {
    setDropdownOpen(false);
    setHighlightedIndex(-1);
  };

  const choose = (result: GeocodeResult) => {
    setQuery(result.primary);
    autocomplete.clear();
    closeDropdown();
    onSelect(result);
  };

  const onChange = (value: string) => {
    setQuery(value);
    setHighlightedIndex(-1);
    setDropdownOpen(true);
    autocomplete.suggest(value);
  };

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    const trimmed = query.trim();
    if (!trimmed) return;
    setDropdownOpen(true);
    const results = await autocomplete.flush(trimmed);
    // A single unambiguous match is selected immediately on submit.
    if (results.length === 1) {
      choose(results[0]);
    }
  };

  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === 'ArrowDown') {
      if (suggestions.length === 0) return;
      event.preventDefault();
      setDropdownOpen(true);
      setHighlightedIndex((index) => (index + 1) % suggestions.length);
    } else if (event.key === 'ArrowUp') {
      if (suggestions.length === 0) return;
      event.preventDefault();
      setHighlightedIndex((index) => (index <= 0 ? suggestions.length - 1 : index - 1));
    } else if (event.key === 'Enter') {
      if (showDropdown && highlightedIndex >= 0 && suggestions[highlightedIndex]) {
        event.preventDefault();
        choose(suggestions[highlightedIndex]);
      }
      // Otherwise let the form submit (onSubmit) run the geocode-on-submit.
    } else if (event.key === 'Escape') {
      if (showDropdown) {
        event.preventDefault();
        closeDropdown();
      }
    }
  };

  const onFocus = () => {
    if (blurTimeoutRef.current) clearTimeout(blurTimeoutRef.current);
    if (autocomplete.status !== 'idle') setDropdownOpen(true);
  };

  const onBlur = () => {
    // Delay so a suggestion click registers before the dropdown closes.
    blurTimeoutRef.current = setTimeout(() => setDropdownOpen(false), 150);
  };

  useEffect(() => {
    return () => {
      if (blurTimeoutRef.current) clearTimeout(blurTimeoutRef.current);
    };
  }, []);

  return (
    <form onSubmit={onSubmit} role="search" aria-label="Search by place">
      <div className={cn('flex gap-sm', compact ? 'items-center' : 'items-end')}>
        <div className="relative min-w-0 flex-1">
          <Input
            label={compact ? undefined : label}
            type="search"
            autoComplete="off"
            placeholder={placeholder}
            value={query}
            onChange={(event) => onChange(event.target.value)}
            onKeyDown={onKeyDown}
            onFocus={onFocus}
            onBlur={onBlur}
            role="combobox"
            aria-expanded={showDropdown}
            aria-controls={listboxId}
            aria-autocomplete="list"
            aria-label={compact ? label : undefined}
            aria-activedescendant={highlightedIndex >= 0 ? optionId(highlightedIndex) : undefined}
            className={
              compact
                ? 'rounded-full bg-surface-container-lowest py-xs pl-md pr-sm shadow-none'
                : undefined
            }
          />
          {showDropdown ? (
            <SuggestionsDropdown
              listboxId={listboxId}
              optionId={optionId}
              status={autocomplete.status}
              results={suggestions}
              highlightedIndex={highlightedIndex}
              onHover={setHighlightedIndex}
              onSelect={choose}
            />
          ) : null}
        </div>
        {compact ? null : (
          <Button type="submit" disabled={autocomplete.status === 'loading'} className="shrink-0">
            <Icon name="search" className="text-[16px] leading-none" />
            Search
          </Button>
        )}
      </div>
    </form>
  );
}

function SuggestionsDropdown({
  listboxId,
  optionId,
  status,
  results,
  highlightedIndex,
  onHover,
  onSelect,
}: {
  listboxId: string;
  optionId: (index: number) => string;
  status: AutocompleteStatus;
  results: GeocodeResult[];
  highlightedIndex: number;
  onHover: (index: number) => void;
  onSelect: (result: GeocodeResult) => void;
}) {
  const showEmpty = status === 'success' && results.length === 0;
  return (
    <div className="absolute left-0 right-0 top-full z-[1200] mt-xs overflow-hidden rounded-xl border border-outline-variant/30 bg-surface shadow-deep">
      {status === 'loading' ? (
        <div className="flex flex-col gap-xs px-md py-sm" role="status" aria-label="Searching places">
          <span className="sr-only">Searching…</span>
          <SkeletonBlock className="h-4 w-2/3" rounded="full" />
          <SkeletonBlock className="h-3 w-1/2" rounded="full" />
        </div>
      ) : null}
      {status === 'error' ? (
        <p className="m-0 px-md py-sm text-label-sm text-error">Could not load suggestions</p>
      ) : null}
      {showEmpty ? (
        <p className="m-0 px-md py-sm text-label-sm text-on-surface-variant">No places found</p>
      ) : null}
      {results.length > 0 ? (
        <ul
          id={listboxId}
          role="listbox"
          className="m-0 flex max-h-60 list-none flex-col overflow-y-auto p-0"
        >
          {results.map((result, index) => (
            <li
              key={result.id}
              id={optionId(index)}
              role="option"
              aria-selected={index === highlightedIndex}
            >
              <button
                type="button"
                // Keep input focus so the click registers before blur-close.
                onMouseDown={(event) => event.preventDefault()}
                onMouseEnter={() => onHover(index)}
                onClick={() => onSelect(result)}
                className={cn(
                  'flex w-full flex-col items-start gap-0 px-md py-sm text-left transition-colors',
                  index === highlightedIndex
                    ? 'bg-surface-container'
                    : 'hover:bg-surface-container-low',
                )}
              >
                <span className="line-clamp-1 text-body-md font-medium text-on-surface">
                  {result.primary}
                </span>
                <span className="line-clamp-1 text-label-sm text-on-surface-variant">
                  {result.secondary || result.displayName}
                </span>
              </button>
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}
