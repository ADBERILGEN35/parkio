import { Card, LoadingState, MetricCard, PageShell } from '@parkio/ui';
import { useQuery } from '@tanstack/react-query';
import { adminApi } from '@/api';
import { FriendlyApiErrorMessage } from '@/components/FriendlyApiErrorMessage';

export function AdminSecurityPage() {
  const query = useQuery({
    queryKey: ['admin', 'security'],
    queryFn: () => adminApi.getSecuritySummary(),
  });

  return (
    <PageShell title="Security">
      <p className="mb-lg mt-0 text-body-md text-on-surface-variant">
        Authentication and account-risk summary from auth-service.
      </p>
      <Card title="Snapshot">
        {query.isPending ? (
          <LoadingState />
        ) : query.isError ? (
          <FriendlyApiErrorMessage error={query.error} />
        ) : (
          <div className="grid grid-cols-2 gap-md md:grid-cols-4">
            <MetricCard label="Suspended" value={query.data.suspendedUsers} icon="block" />
            <MetricCard
              label="Pending verification"
              value={query.data.pendingVerificationUsers}
              icon="mark_email_unread"
            />
            <MetricCard label="Active sessions" value={query.data.activeSessionCount} icon="login" />
            <MetricCard
              label="Reuse detected"
              value={query.data.reuseDetectedSessionCount}
              icon="warning"
            />
          </div>
        )}
      </Card>
    </PageShell>
  );
}
