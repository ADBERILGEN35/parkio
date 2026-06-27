import { Icon } from '@parkio/ui';
import { useState } from 'react';
import { AccountCard } from './profile/AccountCard';
import { ImpactHero } from './profile/ImpactHero';
import { PreferencesCard } from './profile/PreferencesCard';
import { ProfileDetailsCard } from './profile/ProfileDetailsCard';
import { SettingsNav, type SettingsSection } from './profile/SettingsNav';
import { SmartReturnCard } from './profile/SmartReturnCard';
import { TrustProgressCard } from './profile/TrustProgressCard';
import { VehicleCard } from './profile/VehicleCard';

const SECTIONS = [
  { id: 'account', label: 'Profile & Account', icon: 'person' },
  { id: 'vehicle', label: 'Vehicle', icon: 'directions_car' },
  { id: 'notifications', label: 'Notifications', icon: 'notifications' },
  { id: 'smart-return', label: 'Smart Return', icon: 'home_pin' },
  { id: 'trust', label: 'Trust & Progress', icon: 'verified_user' },
] as const satisfies readonly SettingsSection[];

type SectionId = (typeof SECTIONS)[number]['id'];

/**
 * Profile — Settings & Preferences (`/profile`). A Stitch-style settings layout:
 * a persistent impact summary (identity + trust/level/points) sits above a
 * section rail (desktop) / scrollable tab strip (mobile) that toggles between
 * Profile & Account, Vehicle, Notifications and Trust & Progress. All data and
 * mutations are unchanged — only existing backend fields are used and the section
 * tabs are frontend-only (no route changes).
 */
export function ProfilePage() {
  const [section, setSection] = useState<SectionId>('account');

  return (
    <div className="mx-auto w-full max-w-5xl px-md py-lg text-on-background md:px-xl">
      <header className="mb-lg">
        <p className="m-0 flex items-center gap-xs text-label-md font-semibold uppercase tracking-wider text-primary">
          <Icon name="settings" className="text-[16px] leading-none" />
          Account
        </p>
        <h1 className="m-0 mt-sm text-headline-lg-mobile text-on-surface md:text-headline-lg">
          Settings &amp; Preferences
        </h1>
        <p className="m-0 mt-xs text-body-md text-on-surface-variant">
          Manage your profile, vehicle, and notification preferences.
        </p>
      </header>

      <ImpactHero />

      <div className="mt-lg grid grid-cols-1 gap-lg lg:grid-cols-12 lg:items-start">
        <div className="lg:col-span-3">
          <SettingsNav sections={SECTIONS} active={section} onSelect={(id) => setSection(id as SectionId)} />
        </div>

        <div role="tabpanel" className="flex flex-col gap-lg lg:col-span-9">
          {section === 'account' ? (
            <>
              <ProfileDetailsCard />
              <AccountCard />
            </>
          ) : null}
          {section === 'vehicle' ? <VehicleCard /> : null}
          {section === 'notifications' ? <PreferencesCard /> : null}
          {section === 'smart-return' ? <SmartReturnCard /> : null}
          {section === 'trust' ? <TrustProgressCard /> : null}
        </div>
      </div>
    </div>
  );
}
