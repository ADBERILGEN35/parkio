import type { Spot } from '@parkio/types';
import {
  Card,
  EmptyState,
  Icon,
  LoadingState,
  PageShell,
} from '@parkio/ui';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { parkingApi } from '@/api';
import { FriendlyApiErrorMessage } from '@/components/FriendlyApiErrorMessage';
import { SpotResultCard } from '@/components/product/SpotResultCard';

export function MySpotsPage() {
  const { t } = useTranslation('parking');
  const query = useQuery({ queryKey: ['parking', 'my-spots'], queryFn: parkingApi.getMySpots });

  return (
    <PageShell title={t('mySpots.title')}>
      <Card title={t('mySpots.cardTitle')}>
        {query.isPending ? (
          <LoadingState />
        ) : query.isError ? (
          <FriendlyApiErrorMessage error={query.error} />
        ) : query.data.length === 0 ? (
          <EmptyState
            icon="local_parking"
            title={t('mySpots.emptyTitle')}
            description={t('mySpots.emptyDescription')}
            action={
              <Link
                to="/upload"
                className="inline-flex items-center gap-xs rounded-full bg-primary px-lg py-sm text-label-md text-on-primary no-underline shadow-sm transition-all duration-std hover:bg-primary/90"
              >
                <Icon name="add_a_photo" className="text-[16px] leading-none" />
                {t('mySpots.shareFirst')}
              </Link>
            }
          />
        ) : (
          <ul className="m-0 flex list-none flex-col gap-sm p-0">
            {query.data.map((spot) => (
              <MySpotItem key={spot.id} spot={spot} />
            ))}
          </ul>
        )}
      </Card>
    </PageShell>
  );
}

function MySpotItem({ spot }: { spot: Spot }) {
  return <SpotResultCard spot={spot} compact showOwnerMetrics />;
}
