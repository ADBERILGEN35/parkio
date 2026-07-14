import { Card, PageShell, SoftBadge } from '@parkio/ui';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { frontendConfig } from '@/config/env';

export function AdminSystemPage() {
  const { t } = useTranslation('admin');

  return (
    <PageShell title={t('system.title')}>
      <p className="mb-lg mt-0 text-body-md text-on-surface-variant">{t('system.subtitle')}</p>
      <div className="flex flex-col gap-lg">
        <Card title={t('system.environment')}>
          <dl className="grid grid-cols-1 gap-sm sm:grid-cols-2">
            <div>
              <dt className="text-label-md text-on-surface-variant">{t('system.appEnvironment')}</dt>
              <dd className="m-0 mt-xs">
                <SoftBadge tone={frontendConfig.isProductionLike ? 'warning' : 'neutral'}>
                  {frontendConfig.appEnv}
                </SoftBadge>
              </dd>
            </div>
            <div>
              <dt className="text-label-md text-on-surface-variant">{t('system.apiBase')}</dt>
              <dd className="m-0 mt-xs break-all font-mono text-body-sm">{frontendConfig.apiBaseUrl}</dd>
            </div>
          </dl>
        </Card>

        <Card title={t('system.observabilityTitle')}>
          <p className="m-0 text-body-md text-on-surface-variant">{t('system.observabilityBody')}</p>
          <p
            className="mt-sm mb-0 text-body-sm text-on-surface-variant"
            dangerouslySetInnerHTML={{ __html: t('system.observabilityExample') }}
          />
        </Card>

        <Card title={t('system.relatedTitle')}>
          <ul className="m-0 list-disc space-y-xs pl-md text-body-md">
            <li>
              <Link to="/admin/moderation" className="text-primary">
                {t('system.relatedModeration')}
              </Link>{' '}
              {t('system.relatedModerationService')}
            </li>
            <li>
              <Link to="/admin/analytics" className="text-primary">
                {t('system.relatedAnalytics')}
              </Link>{' '}
              {t('system.relatedAnalyticsService')}
            </li>
            <li>
              <Link to="/admin/audit" className="text-primary">
                {t('system.relatedAudit')}
              </Link>{' '}
              {t('system.relatedAuditService')}
            </li>
          </ul>
        </Card>
      </div>
    </PageShell>
  );
}
