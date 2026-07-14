import { zodResolver } from '@hookform/resolvers/zod';
import type { ParkioApiError } from '@parkio/api-client';
import type { AnalyticsMetricType } from '@parkio/types';
import {
  Button,
  Card,
  EmptyState,
  Icon,
  Input,
  LoadingState,
  MetricCard,
  PageShell,
} from '@parkio/ui';
import { userAnalyticsLookupSchema, type UserAnalyticsLookupValues } from '@parkio/validation';
import { useQuery } from '@tanstack/react-query';
import type { TFunction } from 'i18next';
import { useState, type ReactNode } from 'react';
import { useForm } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { analyticsApi } from '@/api';
import { useAuthStore } from '@/auth/store';
import { FriendlyApiErrorMessage } from '@/components/FriendlyApiErrorMessage';
import i18n from 'i18next';

/**
 * Platform analytics dashboard (ADMIN via RoleRoute + gateway). KPI cards
 * plus plain tables — no chart library yet. None of the analytics endpoints take
 * query parameters, so there is nothing to filter client-side.
 */
export function AnalyticsPage() {
  const { t } = useTranslation('analytics');

  return (
    <PageShell title={t('title')}>
      <div className="flex flex-col gap-lg">
        <OverviewCard />
        <div className="grid grid-cols-1 gap-lg lg:grid-cols-2 lg:items-start">
          <ParkingCard />
          <MetricsCard />
        </div>
        <DailyCard />
        <UserLookupCard />
      </div>
    </PageShell>
  );
}

const METRIC_ICON: Record<AnalyticsMetricType, string> = {
  PARKING_CREATED: 'add_location_alt',
  PARKING_VERIFIED: 'verified',
  PARKING_CLAIMED: 'how_to_reg',
  PARKING_REJECTED: 'cancel',
  POINTS_EARNED: 'stars',
  LEVEL_UP: 'military_tech',
  NOTIFICATION_CREATED: 'notifications',
};

function OverviewCard() {
  const { t } = useTranslation('analytics');

  const query = useQuery({
    queryKey: ['analytics', 'overview'],
    queryFn: analyticsApi.getAnalyticsOverview,
  });

  return (
    <Card title={t('overview.title')}>
      {query.isPending ? (
        <LoadingState label={t('common:actions.loading')} />
      ) : query.isError ? (
        <FriendlyApiErrorMessage error={query.error} />
      ) : (
        <div className="grid grid-cols-2 gap-md md:grid-cols-3 xl:grid-cols-4">
          <MetricCard
            label={t('overview.spotsCreated')}
            value={query.data.totalParkingCreated}
            icon="add_location_alt"
          />
          <MetricCard
            label={t('overview.verifications')}
            value={query.data.totalParkingVerified}
            icon="verified"
          />
          <MetricCard label={t('overview.claims')} value={query.data.totalParkingClaimed} icon="how_to_reg" />
          <MetricCard
            label={t('overview.rejections')}
            value={query.data.totalParkingRejected}
            icon="cancel"
          />
          <MetricCard label={t('overview.pointsEarned')} value={query.data.totalPointsEarned} icon="stars" />
          <MetricCard label={t('overview.levelUps')} value={query.data.totalLevelUps} icon="military_tech" />
          <MetricCard
            label={t('overview.notifications')}
            value={query.data.totalNotificationsCreated}
            icon="notifications"
          />
        </div>
      )}
    </Card>
  );
}

function DailyCard() {
  const { t } = useTranslation('analytics');

  const query = useQuery({
    queryKey: ['analytics', 'daily'],
    queryFn: analyticsApi.getDailyAnalytics,
  });

  return (
    <Card title={t('daily.title')}>
      {query.isPending ? (
        <LoadingState label={t('common:actions.loading')} />
      ) : query.isError ? (
        <FriendlyApiErrorMessage error={query.error} />
      ) : query.data.length === 0 ? (
        <EmptyState icon="calendar_month" title={t('daily.emptyTitle')} />
      ) : (
        <DataTable
          headers={[
            t('daily.headers.date'),
            t('daily.headers.metric'),
            t('daily.headers.events'),
            t('daily.headers.sum'),
          ]}
        >
          {query.data.map((row) => (
            <tr key={`${row.date}-${row.metricType}`} className="border-t border-outline-variant/30">
              <Td>{row.date}</Td>
              <Td>
                <MetricLabel type={row.metricType} />
              </Td>
              <Td>{row.eventCount}</Td>
              <Td>{row.sumValue}</Td>
            </tr>
          ))}
        </DataTable>
      )}
    </Card>
  );
}

function ParkingCard() {
  const { t } = useTranslation('analytics');

  const query = useQuery({
    queryKey: ['analytics', 'parking'],
    queryFn: analyticsApi.getParkingAnalytics,
  });

  return (
    <Card title={t('parking.title')}>
      {query.isPending ? (
        <LoadingState label={t('common:actions.loading')} />
      ) : query.isError ? (
        <FriendlyApiErrorMessage error={query.error} />
      ) : query.data.length === 0 ? (
        <EmptyState icon="filter_alt" title={t('parking.emptyTitle')} />
      ) : (
        <DataTable
          headers={[t('parking.headers.metric'), t('parking.headers.events'), t('parking.headers.sum')]}
        >
          {query.data.map((row) => (
            <tr key={row.metricType} className="border-t border-outline-variant/30">
              <Td>
                <MetricLabel type={row.metricType} />
              </Td>
              <Td>{row.eventCount}</Td>
              <Td>{row.sumValue}</Td>
            </tr>
          ))}
        </DataTable>
      )}
    </Card>
  );
}

function MetricsCard() {
  const { t } = useTranslation('analytics');

  const query = useQuery({
    queryKey: ['analytics', 'metrics'],
    queryFn: analyticsApi.getAnalyticsMetrics,
  });

  return (
    <Card title={t('metrics.title')}>
      {query.isPending ? (
        <LoadingState label={t('common:actions.loading')} />
      ) : query.isError ? (
        <FriendlyApiErrorMessage error={query.error} />
      ) : query.data.length === 0 ? (
        <EmptyState icon="analytics" title={t('metrics.emptyTitle')} />
      ) : (
        <DataTable
          headers={[
            t('metrics.headers.metric'),
            t('metrics.headers.totalCount'),
            t('metrics.headers.totalValue'),
          ]}
        >
          {query.data.map((row) => (
            <tr key={row.metricType} className="border-t border-outline-variant/30">
              <Td>
                <MetricLabel type={row.metricType} />
              </Td>
              <Td>{row.totalCount}</Td>
              <Td>{row.totalValue}</Td>
            </tr>
          ))}
        </DataTable>
      )}
    </Card>
  );
}

/**
 * The backend restricts `/analytics/users/{userId}` to the caller's own id —
 * any other id returns 403 FORBIDDEN even for moderators/admins, so this lookup
 * is mostly useful with "Use my id".
 */
function UserLookupCard() {
  const { t } = useTranslation('analytics');
  const ownUserId = useAuthStore((s) => s.user?.id ?? null);
  const [lookupUserId, setLookupUserId] = useState<string | null>(null);

  const {
    register,
    handleSubmit,
    setValue,
    formState: { errors },
  } = useForm<UserAnalyticsLookupValues>({
    resolver: zodResolver(userAnalyticsLookupSchema),
    defaultValues: { userId: '' },
  });

  const query = useQuery({
    queryKey: ['analytics', 'user', lookupUserId],
    queryFn: () => analyticsApi.getUserAnalytics(lookupUserId as string),
    enabled: lookupUserId !== null,
  });

  const onSubmit = handleSubmit((values) => setLookupUserId(values.userId));

  return (
    <Card title={t('userLookup.title')}>
      <p className="m-0 mb-sm flex items-start gap-xs text-label-sm text-on-surface-variant">
        <Icon name="info" className="text-[16px] leading-none" />
        {t('userLookup.info')}
      </p>
      <form onSubmit={onSubmit}>
        <fieldset disabled={query.isFetching} className="m-0 flex flex-col gap-sm border-0 p-0">
          <Input
            label={t('userLookup.userIdLabel')}
            placeholder={t('userLookup.userIdPlaceholder')}
            className="font-mono"
            error={errors.userId?.message}
            {...register('userId')}
          />
          <div className="flex flex-wrap gap-sm">
            <Button type="submit" disabled={query.isFetching}>
              {query.isFetching ? t('userLookup.loading') : t('userLookup.fetch')}
            </Button>
            {ownUserId ? (
              <Button
                type="button"
                variant="secondary"
                onClick={() => setValue('userId', ownUserId, { shouldValidate: true })}
              >
                {t('userLookup.useMyId')}
              </Button>
            ) : null}
          </div>
        </fieldset>
      </form>

      {lookupUserId === null ? null : query.isPending ? (
        <div className="mt-sm">
          <LoadingState label={t('common:actions.loading')} />
        </div>
      ) : query.isError ? (
        <div className="mt-sm">
          <FriendlyApiErrorMessage error={query.error} mapper={(error) => mapUserLookupError(t, error)} />
        </div>
      ) : query.data.length === 0 ? (
        <div className="mt-sm">
          <EmptyState icon="person_search" title={t('userLookup.emptyTitle')} />
        </div>
      ) : (
        <div className="mt-md">
          <DataTable
            headers={[
              t('userLookup.headers.metric'),
              t('userLookup.headers.events'),
              t('userLookup.headers.sum'),
            ]}
          >
            {query.data.map((row) => (
              <tr key={row.metricType} className="border-t border-outline-variant/30">
                <Td>
                  <MetricLabel type={row.metricType} />
                </Td>
                <Td>{row.eventCount}</Td>
                <Td>{row.sumValue}</Td>
              </tr>
            ))}
          </DataTable>
        </div>
      )}
    </Card>
  );
}

/* ------------------------------------------------------------------------- */
/* Table primitives                                                           */
/* ------------------------------------------------------------------------- */

function DataTable({ headers, children }: { headers: string[]; children: ReactNode }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full border-collapse text-body-md">
        <thead>
          <tr>
            {headers.map((header) => (
              <th
                key={header}
                className="px-sm py-xs text-left text-label-sm font-semibold uppercase tracking-wider text-on-surface-variant"
              >
                {header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>{children}</tbody>
      </table>
    </div>
  );
}

function Td({ children }: { children: ReactNode }) {
  return <td className="px-sm py-sm text-on-surface">{children}</td>;
}

function MetricLabel({ type }: { type: AnalyticsMetricType }) {
  const { t } = useTranslation('analytics');
  const key = `metricType.${type}`;
  const translated = t(key);
  const label = translated === key ? i18n.t('common:unknownEnum') : translated;

  return (
    <span className="flex items-center gap-xs">
      <Icon
        name={METRIC_ICON[type] ?? 'analytics'}
        className="text-[16px] leading-none text-on-surface-variant"
      />
      {label}
    </span>
  );
}

/** Friendly wording for the own-analytics-only restriction; null falls back to ApiError. */
function mapUserLookupError(t: TFunction<'analytics'>, error: ParkioApiError): string | null {
  if (error.status === 403) {
    return t('userLookup.errorForbidden');
  }
  return null;
}
