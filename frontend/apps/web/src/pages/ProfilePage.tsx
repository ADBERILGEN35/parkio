import { PageShell } from '@parkio/ui';
import { Link } from 'react-router-dom';
import { AppNav } from '@/components/AppNav';
import { AccountCard } from './profile/AccountCard';
import { ImpactHero } from './profile/ImpactHero';
import { PreferencesCard } from './profile/PreferencesCard';
import { ProfileDetailsCard } from './profile/ProfileDetailsCard';
import { VehicleCard } from './profile/VehicleCard';

/**
 * Profile / Impact Hub Beta (`/profile`). Impact-first layout: the hero (identity
 * + trust/level/points stats) sits above the fold, with the editable forms and
 * account settings below. On desktop the forms take the wider left column and
 * account settings the right; on mobile everything stacks, stats first, sign-out
 * last. All data and mutations are unchanged — only the presentation is new.
 */
export function ProfilePage() {
  return (
    <PageShell title="Profile">
      <AppNav />
      <div className="flex flex-col gap-lg">
        <ImpactHero />

        <div className="grid grid-cols-1 gap-lg lg:grid-cols-3 lg:items-start">
          <div className="flex flex-col gap-lg lg:col-span-2">
            <ProfileDetailsCard />
            <PreferencesCard />
            <VehicleCard />
            <p className="m-0 text-label-sm text-on-surface-variant">
              <Link to="/gamification" className="text-primary hover:underline">
                View level progress and points history
              </Link>
            </p>
          </div>

          <aside className="flex flex-col gap-lg">
            <AccountCard />
          </aside>
        </div>
      </div>
    </PageShell>
  );
}
