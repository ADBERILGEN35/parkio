import { Icon, LoadingState, MetricCard, SoftBadge } from '@parkio/ui';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { usersApi } from '@/api';
import { FriendlyApiErrorMessage } from '@/components/FriendlyApiErrorMessage';
import { SettingsSectionCard } from '@/components/product/SettingsSectionCard';
import { enumLabel } from '@/lib/format';
import { trustBandTone } from './accountVisuals';

export function TrustProgressCard() {
  const { t } = useTranslation(['settings', 'common']);
  const stats = useQuery({ queryKey: ['me', 'stats'], queryFn: usersApi.getMyStats });

  return (
    <SettingsSectionCard
      title={t('trust.title')}
      icon="verified_user"
      description={t('trust.description')}
    >
      {stats.isPending ? (
        <LoadingState label={t('trust.loading')} />
      ) : stats.isError ? (
        <FriendlyApiErrorMessage error={stats.error} />
      ) : (
        <div className="flex flex-col gap-md">
          <div className="grid grid-cols-2 gap-md">
            <MetricCard label={t('trust.totalPoints')} value={stats.data.totalPoints} icon="stars" />
            <MetricCard
              label={t('trust.currentLevel')}
              value={stats.data.currentLevel}
              icon="military_tech"
            />
            <MetricCard label={t('trust.trustScore')} value={stats.data.trustScore} icon="verified_user" />
            <MetricCard
              label={t('trust.trustBand')}
              icon="shield"
              value={
                <SoftBadge tone={trustBandTone(stats.data.trustBand)}>
                  {enumLabel(stats.data.trustBand, t)}
                </SoftBadge>
              }
            />
          </div>

          <Link
            to="/gamification"
            className="inline-flex items-center gap-xs self-start text-label-md font-semibold text-primary hover:underline"
          >
            <Icon name="trending_up" className="text-[18px] leading-none" />
            {t('trust.viewHistory')}
          </Link>

          <p className="m-0 text-label-sm text-on-surface-variant">{t('trust.note')}</p>
        </div>
      )}
    </SettingsSectionCard>
  );
}
