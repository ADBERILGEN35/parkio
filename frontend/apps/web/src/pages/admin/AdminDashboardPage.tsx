import { useQuery } from '@tanstack/react-query';
import { Card, LoadingState, MetricCard, PageShell } from '@parkio/ui';
import { useTranslation } from 'react-i18next';
import { adminApi, analyticsApi } from '@/api';
import { FriendlyApiErrorMessage } from '@/components/FriendlyApiErrorMessage';

export function AdminDashboardPage() {
  const { t } = useTranslation('admin');
  const dashboard = useQuery({
    queryKey: ['admin', 'dashboard'],
    queryFn: () => adminApi.getDashboard(),
  });
  const overview = useQuery({
    queryKey: ['analytics', 'overview'],
    queryFn: () => analyticsApi.getAnalyticsOverview(),
    retry: false,
  });

  return (
    <PageShell title={t('dashboard.title')}>
      <p className="mb-lg mt-0 text-body-md text-on-surface-variant">{t('dashboard.subtitle')}</p>
      <div className="flex flex-col gap-lg">
        <Card title={t('dashboard.accounts')}>
          {dashboard.isPending ? (
            <LoadingState />
          ) : dashboard.isError ? (
            <FriendlyApiErrorMessage error={dashboard.error} />
          ) : (
            <div className="grid grid-cols-2 gap-md md:grid-cols-3 xl:grid-cols-4">
              <MetricCard label={t('dashboard.metrics.totalUsers')} value={dashboard.data.totalUsers} icon="group" />
              <MetricCard label={t('dashboard.metrics.verified')} value={dashboard.data.verifiedUsers} icon="verified" />
              <MetricCard
                label={t('dashboard.metrics.unverified')}
                value={dashboard.data.unverifiedUsers}
                icon="mark_email_unread"
              />
              <MetricCard
                label={t('dashboard.metrics.activeSessions')}
                value={dashboard.data.activeSessionCount}
                icon="login"
              />
              <MetricCard
                label={t('dashboard.metrics.registeredToday')}
                value={dashboard.data.registrationsToday}
                icon="person_add"
              />
              <MetricCard
                label={t('dashboard.metrics.last7Days')}
                value={dashboard.data.registrationsLast7Days}
                icon="calendar_month"
              />
              <MetricCard
                label={t('dashboard.metrics.last30Days')}
                value={dashboard.data.registrationsLast30Days}
                icon="date_range"
              />
              <MetricCard
                label={t('dashboard.metrics.verificationRate')}
                value={`${Math.round(dashboard.data.verificationConversionRate * 100)}%`}
                icon="percent"
              />
            </div>
          )}
        </Card>

        <Card title={t('dashboard.platformActivity')}>
          {overview.isPending ? (
            <LoadingState />
          ) : overview.isError ? (
            <p className="m-0 text-body-md text-on-surface-variant">{t('dashboard.analyticsUnavailable')}</p>
          ) : (
            <div className="grid grid-cols-2 gap-md md:grid-cols-3 xl:grid-cols-4">
              <MetricCard
                label={t('dashboard.metrics.spotsCreated')}
                value={overview.data.totalParkingCreated}
                icon="add_location_alt"
              />
              <MetricCard
                label={t('dashboard.metrics.verified')}
                value={overview.data.totalParkingVerified}
                icon="verified"
              />
              <MetricCard
                label={t('dashboard.metrics.claims')}
                value={overview.data.totalParkingClaimed}
                icon="how_to_reg"
              />
              <MetricCard
                label={t('dashboard.metrics.rejected')}
                value={overview.data.totalParkingRejected}
                icon="cancel"
              />
              <MetricCard
                label={t('dashboard.metrics.pointsEarned')}
                value={overview.data.totalPointsEarned}
                icon="stars"
              />
              <MetricCard
                label={t('dashboard.metrics.notifications')}
                value={overview.data.totalNotificationsCreated}
                icon="notifications"
              />
            </div>
          )}
        </Card>
      </div>
    </PageShell>
  );
}
