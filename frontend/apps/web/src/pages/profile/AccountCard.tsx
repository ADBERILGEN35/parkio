import { Button, Card, Icon, SoftBadge } from '@parkio/ui';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { usersApi } from '@/api';
import { performLogout } from '@/auth/logout';
import { useAuthStore } from '@/auth/store';
import { humanizeEnum } from '@/lib/format';
import { accountStatusTone } from './accountVisuals';

/**
 * Account summary + settings: email, status, roles and (best-effort) the
 * platform auth user id, plus the sign-out action. Identity comes from the
 * auth session (so sign-out never depends on a network call); `authUserId`
 * is enriched from the profile query when available.
 */
export function AccountCard() {
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);
  const roles = useAuthStore((s) => s.roles);
  const status = useAuthStore((s) => s.status);
  const [signingOut, setSigningOut] = useState(false);

  // Best-effort enrichment only — already cached by ImpactHero, never blocks sign-out.
  const profile = useQuery({ queryKey: ['me', 'profile'], queryFn: usersApi.getMyProfile });

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
    <Card title="Account">
      <dl className="m-0 flex flex-col gap-md">
        <Row label="Email" value={user?.email ?? profile.data?.email ?? '—'} />
        <div className="flex flex-col gap-xs">
          <dt className="text-label-sm font-medium text-on-surface-variant">Status</dt>
          <dd className="m-0">
            {status ? (
              <SoftBadge tone={accountStatusTone(status)} icon="account_circle">
                {humanizeEnum(status)}
              </SoftBadge>
            ) : (
              <span className="text-body-md text-on-surface-variant">—</span>
            )}
          </dd>
        </div>
        <div className="flex flex-col gap-xs">
          <dt className="text-label-sm font-medium text-on-surface-variant">Roles</dt>
          <dd className="m-0 flex flex-wrap gap-xs">
            {roles.length > 0 ? (
              roles.map((role) => (
                <SoftBadge key={role} tone="primary" icon="badge">
                  {humanizeEnum(role)}
                </SoftBadge>
              ))
            ) : (
              <span className="text-body-md text-on-surface-variant">—</span>
            )}
          </dd>
        </div>
        {profile.data?.authUserId ? (
          <div className="flex flex-col gap-xs">
            <dt className="text-label-sm font-medium text-on-surface-variant">Auth user id</dt>
            <dd className="m-0 break-all font-mono text-label-sm text-on-surface-variant">
              {profile.data.authUserId}
            </dd>
          </div>
        ) : null}
      </dl>

      <div className="mt-lg border-t border-outline-variant/30 pt-md">
        <Button type="button" variant="outline" onClick={onSignOut} disabled={signingOut}>
          <Icon name="logout" className="text-[16px] leading-none" />
          {signingOut ? 'Signing out…' : 'Sign out'}
        </Button>
      </div>
    </Card>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex flex-col gap-xs">
      <dt className="text-label-sm font-medium text-on-surface-variant">{label}</dt>
      <dd className="m-0 break-all text-body-md text-on-surface">{value}</dd>
    </div>
  );
}
