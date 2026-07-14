import { Card, LoadingState, MetricCard, PageShell } from '@parkio/ui';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { adminApi } from '@/api';
import { FriendlyApiErrorMessage } from '@/components/FriendlyApiErrorMessage';

export function AdminSecurityPage() {
  const { t } = useTranslation('admin');
  const query = useQuery({
    queryKey: ['admin', 'security'],
    queryFn: () => adminApi.getSecuritySummary(),
  });

  return (
    <PageShell title={t('security.title')}>
      <p className="mb-lg mt-0 text-body-md text-on-surface-variant">{t('security.subtitle')}</p>
      <Card title={t('security.snapshot')}>
        {query.isPending ? (
          <LoadingState />
        ) : query.isError ? (
          <FriendlyApiErrorMessage error={query.error} />
        ) : (
          <div className="grid grid-cols-2 gap-md md:grid-cols-4">
            <MetricCard label={t('security.metrics.suspended')} value={query.data.suspendedUsers} icon="block" />
            <MetricCard
              label={t('security.metrics.pendingVerification')}
              value={query.data.pendingVerificationUsers}
              icon="mark_email_unread"
            />
            <MetricCard
              label={t('security.metrics.activeSessions')}
              value={query.data.activeSessionCount}
              icon="login"
            />
            <MetricCard
              label={t('security.metrics.reuseDetected')}
              value={query.data.reuseDetectedSessionCount}
              icon="warning"
            />
          </div>
        )}
      </Card>
    </PageShell>
  );
}
