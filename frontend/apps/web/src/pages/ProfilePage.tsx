import { Button, Card, PageShell } from '@parkio/ui';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { performLogout } from '@/auth/logout';
import { useAuthStore } from '@/auth/store';
import { AppNav } from '@/components/AppNav';
import { PreferencesCard } from './profile/PreferencesCard';
import { ProfileDetailsCard } from './profile/ProfileDetailsCard';
import { StatsCard } from './profile/StatsCard';
import { VehicleCard } from './profile/VehicleCard';

export function ProfilePage() {
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);
  const roles = useAuthStore((s) => s.roles);
  const status = useAuthStore((s) => s.status);
  const [signingOut, setSigningOut] = useState(false);

  const onSignOut = async () => {
    setSigningOut(true);
    try {
      await performLogout();
      navigate('/login', { replace: true });
    } finally {
      setSigningOut(false);
    }
  };

  return (
    <PageShell title="Profile">
      <AppNav />
      <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem', maxWidth: '32rem' }}>
        <Card title="Account">
          <p style={{ margin: '0.25rem 0' }}>Email: {user?.email ?? '—'}</p>
          <p style={{ margin: '0.25rem 0' }}>Status: {status ?? '—'}</p>
          <p style={{ margin: '0.25rem 0' }}>Roles: {roles.join(', ') || '—'}</p>
          <Button onClick={onSignOut} disabled={signingOut} style={{ marginTop: '1rem' }}>
            {signingOut ? 'Signing out…' : 'Sign out'}
          </Button>
        </Card>
        <ProfileDetailsCard />
        <PreferencesCard />
        <VehicleCard />
        <StatsCard />
      </div>
    </PageShell>
  );
}
