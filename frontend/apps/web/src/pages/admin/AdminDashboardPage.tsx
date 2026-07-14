import { useQuery } from '@tanstack/react-query';
import { Card, LoadingState, MetricCard, PageShell } from '@parkio/ui';
import { adminApi, analyticsApi } from '@/api';
import { FriendlyApiErrorMessage } from '@/components/FriendlyApiErrorMessage';

export function AdminDashboardPage() {
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
    <PageShell title="Admin dashboard">
      <p className="mb-lg mt-0 text-body-md text-on-surface-variant">
        Operational overview for the hosted Parkio beta.
      </p>
      <div className="flex flex-col gap-lg">
        <Card title="Accounts">
          {dashboard.isPending ? (
            <LoadingState />
          ) : dashboard.isError ? (
            <FriendlyApiErrorMessage error={dashboard.error} />
          ) : (
            <div className="grid grid-cols-2 gap-md md:grid-cols-3 xl:grid-cols-4">
              <MetricCard label="Total users" value={dashboard.data.totalUsers} icon="group" />
              <MetricCard label="Verified" value={dashboard.data.verifiedUsers} icon="verified" />
              <MetricCard label="Unverified" value={dashboard.data.unverifiedUsers} icon="mark_email_unread" />
              <MetricCard
                label="Active sessions"
                value={dashboard.data.activeSessionCount}
                icon="login"
              />
              <MetricCard
                label="Registered today"
                value={dashboard.data.registrationsToday}
                icon="person_add"
              />
              <MetricCard
                label="Last 7 days"
                value={dashboard.data.registrationsLast7Days}
                icon="calendar_month"
              />
              <MetricCard
                label="Last 30 days"
                value={dashboard.data.registrationsLast30Days}
                icon="date_range"
              />
              <MetricCard
                label="Verification rate"
                value={`${Math.round(dashboard.data.verificationConversionRate * 100)}%`}
                icon="percent"
              />
            </div>
          )}
        </Card>

        <Card title="Platform activity (analytics)">
          {overview.isPending ? (
            <LoadingState />
          ) : overview.isError ? (
            <p className="m-0 text-body-md text-on-surface-variant">
              Platform analytics unavailable. Account KPIs above still reflect auth-service data.
            </p>
          ) : (
            <div className="grid grid-cols-2 gap-md md:grid-cols-3 xl:grid-cols-4">
              <MetricCard label="Spots created" value={overview.data.totalParkingCreated} icon="add_location_alt" />
              <MetricCard label="Verified" value={overview.data.totalParkingVerified} icon="verified" />
              <MetricCard label="Claims" value={overview.data.totalParkingClaimed} icon="how_to_reg" />
              <MetricCard label="Rejected" value={overview.data.totalParkingRejected} icon="cancel" />
              <MetricCard label="Points earned" value={overview.data.totalPointsEarned} icon="stars" />
              <MetricCard label="Notifications" value={overview.data.totalNotificationsCreated} icon="notifications" />
            </div>
          )}
        </Card>
      </div>
    </PageShell>
  );
}
