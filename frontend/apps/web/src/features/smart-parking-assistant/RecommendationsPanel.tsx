import { Button, EmptyState, ErrorMessage, Icon, MapSearchSkeleton } from '@parkio/ui';
import type { Destination, ParkingCandidate } from '@parkio/types';
import type { UseQueryResult } from '@tanstack/react-query';
import type { RecommendationResponse } from '@parkio/types';
import { useTranslation } from 'react-i18next';
import { RecommendationCard } from './RecommendationCard';

export type RecommendationsPanelProps = {
  destination: Destination;
  recommendations: UseQueryResult<RecommendationResponse, Error>;
  selectedCandidateId: string | null;
  onSelectCandidate: (candidate: ParkingCandidate) => void;
  onClearDestination: () => void;
  onChangeDestination: () => void;
};

export function RecommendationsPanel({
  destination,
  recommendations,
  selectedCandidateId,
  onSelectCandidate,
  onClearDestination,
  onChangeDestination,
}: RecommendationsPanelProps) {
  const { t } = useTranslation('map');
  const data = recommendations.data;
  const candidates = data?.candidates ?? [];
  const isLoading = recommendations.isFetching && !data;
  const isError = recommendations.isError && !data;

  return (
    <section
      className="flex flex-col gap-md"
      data-testid="assistant-recommendations-panel"
      aria-labelledby="assistant-recommendations-heading"
    >
      <header className="flex flex-col gap-xs">
        <div className="flex items-start justify-between gap-sm">
          <div className="min-w-0 flex-1">
            <p className="m-0 text-label-sm font-medium text-on-surface-variant">
              {t('assistant.destinationLabel')}
            </p>
            <h2
              id="assistant-recommendations-heading"
              className="m-0 break-words text-title-md text-on-surface"
            >
              {t('assistant.recommendationsTitle', { label: destination.label })}
            </h2>
          </div>
          <button
            type="button"
            aria-label={t('assistant.clearDestination')}
            onClick={onClearDestination}
            className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full text-on-surface-variant hover:bg-surface-container focus:outline-none focus-visible:ring-2 focus-visible:ring-primary"
          >
            <Icon name="close" className="text-[18px] leading-none" />
          </button>
        </div>
        <div className="flex flex-wrap gap-xs">
          <Button type="button" variant="secondary" onClick={onChangeDestination}>
            {t('assistant.changeDestination')}
          </Button>
          {data ? (
            <span className="inline-flex items-center text-label-sm text-on-surface-variant">
              {t('assistant.candidateCount', { count: candidates.length })}
            </span>
          ) : null}
        </div>
        {data?.partial ? (
          <p className="m-0 rounded-xl bg-surface-container px-md py-sm text-label-sm text-on-surface" role="status">
            {t('assistant.partialInventory')}
          </p>
        ) : null}
      </header>

      {isLoading ? (
        <div aria-busy="true" aria-live="polite">
          <MapSearchSkeleton />
        </div>
      ) : null}

      {isError ? (
        <div className="flex flex-col gap-sm" role="alert">
          <ErrorMessage message={t('assistant.recommendationsError')} />
          <Button type="button" onClick={() => void recommendations.refetch()}>
            {t('assistant.retry')}
          </Button>
        </div>
      ) : null}

      {!isLoading && !isError && data && candidates.length === 0 ? (
        <EmptyState
          icon="local_parking"
          title={t('assistant.emptyTitle')}
          description={t('assistant.emptyDescription')}
        />
      ) : null}

      {!isLoading && candidates.length > 0 ? (
        <ol className="m-0 flex list-none flex-col gap-sm p-0">
          {candidates.map((candidate, index) => (
            <li key={candidate.id}>
              <RecommendationCard
                candidate={candidate}
                rankIndex={index}
                selected={candidate.id === selectedCandidateId}
                onSelect={onSelectCandidate}
              />
            </li>
          ))}
        </ol>
      ) : null}
    </section>
  );
}
