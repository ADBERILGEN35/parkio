import { Icon } from '@parkio/ui';
import { useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router-dom';
import { frontendConfig } from '@/config/env';
import { AccountCard } from './profile/AccountCard';
import { ImpactHero } from './profile/ImpactHero';
import { PreferencesCard } from './profile/PreferencesCard';
import { ProfileDetailsCard } from './profile/ProfileDetailsCard';
import { SettingsNav, type SettingsSection } from './profile/SettingsNav';
import { SmartReturnCard } from './profile/SmartReturnCard';
import { TrustProgressCard } from './profile/TrustProgressCard';
import { ParkingHistoryCard } from './profile/ParkingHistoryCard';
import { VehicleCard } from './profile/VehicleCard';

type SectionId =
  | 'account'
  | 'vehicle'
  | 'notifications'
  | 'smart-return'
  | 'parking-history'
  | 'trust';

export interface ProfilePageProps {
  smartReturnEnabled?: boolean;
}

/**
 * Profile — Settings & Preferences (`/profile`). A Stitch-style settings layout:
 * a persistent impact summary (identity + trust/level/points) sits above a
 * section rail (desktop) / scrollable tab strip (mobile) that toggles between
 * Profile & Account, Vehicle, Notifications, Parking History, and Trust & Progress.
 */
export function ProfilePage({ smartReturnEnabled = frontendConfig.features.smartReturn }: ProfilePageProps) {
  const { t } = useTranslation('settings');
  const sections = useMemo((): readonly SettingsSection[] => {
    const base: SettingsSection[] = [
      { id: 'account', label: t('sections.account'), icon: 'person' },
      { id: 'vehicle', label: t('sections.vehicle'), icon: 'directions_car' },
      { id: 'notifications', label: t('sections.notifications'), icon: 'notifications' },
      { id: 'parking-history', label: t('sections.parkingHistory'), icon: 'history' },
      { id: 'trust', label: t('sections.trust'), icon: 'verified_user' },
    ];
    if (!smartReturnEnabled) return base;
    return [
      base[0]!,
      base[1]!,
      base[2]!,
      { id: 'smart-return', label: t('sections.smartReturn'), icon: 'home_pin' },
      base[3]!,
      base[4]!,
    ];
  }, [smartReturnEnabled, t]);

  const [searchParams] = useSearchParams();
  const requestedSection = searchParams.get('section');
  const initialSection: SectionId =
    smartReturnEnabled && requestedSection === 'smart-return' ? 'smart-return' : 'account';
  const [section, setSection] = useState<SectionId>(initialSection);

  useEffect(() => {
    if (!requestedSection) return;
    const validIds = new Set(sections.map((s) => s.id));
    if (validIds.has(requestedSection as SectionId)) {
      setSection(requestedSection as SectionId);
    }
  }, [requestedSection, sections]);

  return (
    <div className="mx-auto w-full max-w-5xl min-w-0 px-md py-lg text-on-background md:px-xl">
      <header className="mb-lg min-w-0">
        <p className="m-0 flex items-center gap-xs text-label-md font-semibold uppercase tracking-wider text-primary">
          <Icon name="settings" className="text-[16px] leading-none" />
          {t('page.eyebrow')}
        </p>
        <h1 className="m-0 mt-sm break-words text-headline-lg-mobile text-on-surface md:text-headline-lg">
          {t('page.title')}
        </h1>
        <p className="m-0 mt-xs text-body-md text-on-surface-variant">{t('page.description')}</p>
      </header>

      <ImpactHero />

      <div className="mt-lg grid min-w-0 grid-cols-1 gap-lg lg:grid-cols-12 lg:items-start">
        <div className="min-w-0 lg:col-span-3">
          <SettingsNav sections={sections} active={section} onSelect={(id) => setSection(id as SectionId)} />
        </div>

        <div role="tabpanel" className="flex min-w-0 flex-col gap-lg lg:col-span-9">
          {section === 'account' ? (
            <>
              <ProfileDetailsCard />
              <AccountCard />
            </>
          ) : null}
          {section === 'vehicle' ? <VehicleCard /> : null}
          {section === 'notifications' ? <PreferencesCard /> : null}
          {section === 'smart-return' && smartReturnEnabled ? (
            <SmartReturnCard autoFocusToday={requestedSection === 'smart-return'} />
          ) : null}
          {section === 'parking-history' ? <ParkingHistoryCard /> : null}
          {section === 'trust' ? <TrustProgressCard /> : null}
        </div>
      </div>
    </div>
  );
}
