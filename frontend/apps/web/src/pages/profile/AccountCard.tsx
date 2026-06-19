import { zodResolver } from '@hookform/resolvers/zod';
import {
  changePasswordSchema,
  passwordRequirementState,
  passwordRequirements,
  type ChangePasswordFormValues,
} from '@parkio/validation';
import { Button, Card, Icon, Input, SoftBadge } from '@parkio/ui';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { useNavigate } from 'react-router-dom';
import { authApi, usersApi } from '@/api';
import { describeAuthError } from '@/api/error-messages';
import { performLogout } from '@/auth/logout';
import { useAuthStore } from '@/auth/store';
import { humanizeEnum } from '@/lib/format';
import { showError, showSuccess, showWarning } from '@/lib/toast';
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
  const clearSession = useAuthStore((s) => s.clearSession);
  const [signingOut, setSigningOut] = useState(false);
  const [loggingOutAll, setLoggingOutAll] = useState(false);
  const [passwordMessage, setPasswordMessage] = useState<string | null>(null);
  const [passwordError, setPasswordError] = useState<string | null>(null);

  // Best-effort enrichment only — already cached by ImpactHero, never blocks sign-out.
  const profile = useQuery({ queryKey: ['me', 'profile'], queryFn: usersApi.getMyProfile });
  const {
    register,
    handleSubmit,
    reset,
    watch,
    formState: { errors, isSubmitting },
  } = useForm<ChangePasswordFormValues>({
    resolver: zodResolver(changePasswordSchema),
    defaultValues: { currentPassword: '', password: '', confirmPassword: '' },
  });
  const passwordValue = watch('password') ?? '';
  const passwordState = passwordRequirementState(passwordValue);

  const onSignOut = async () => {
    setSigningOut(true);
    try {
      await performLogout();
      showSuccess('Signed out.');
      navigate('/login', { replace: true });
    } finally {
      setSigningOut(false);
    }
  };

  const onLogoutAll = async () => {
    if (!window.confirm('Log out of all devices? You will need to sign in again.')) {
      return;
    }
    setLoggingOutAll(true);
    try {
      await authApi.logoutAll();
    } catch {
      showWarning('Could not reach the server, but this browser was signed out.');
      // The local session is still cleared so this browser cannot keep using a stale token.
    } finally {
      clearSession();
      setLoggingOutAll(false);
      navigate('/login', { replace: true });
    }
  };

  const onChangePassword = handleSubmit(async (values) => {
    setPasswordError(null);
    setPasswordMessage(null);
    try {
      await authApi.changePassword({
        currentPassword: values.currentPassword,
        newPassword: values.password,
      });
      reset();
      setPasswordMessage('Password changed. Please sign in again.');
      showSuccess('Password changed. Please sign in again.');
      clearSession();
      navigate('/login', { replace: true });
    } catch (error) {
      const friendly = describeAuthError(error, 'Password change failed. Please try again.');
      setPasswordError(friendly.message);
      showError(friendly.message);
    }
  });

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
        <form onSubmit={onChangePassword} className="mb-lg flex flex-col gap-sm">
          <h3 className="m-0 text-title-md text-on-surface">Change password</h3>
          <Input
            label="Current password"
            type="password"
            autoComplete="current-password"
            error={errors.currentPassword?.message}
            {...register('currentPassword')}
          />
          <Input
            label="New password"
            type="password"
            autoComplete="new-password"
            error={errors.password?.message}
            {...register('password')}
          />
          <ul className="m-0 grid list-none gap-1 p-0 text-label-sm text-on-surface-variant">
            {passwordRequirements.map((requirement) => {
              const met = passwordState[requirement.id];
              return (
                <li key={requirement.id} className={met ? 'text-success' : 'text-on-surface-variant'}>
                  {met ? 'Met:' : 'Needed:'} {requirement.label}
                </li>
              );
            })}
          </ul>
          <Input
            label="Confirm new password"
            type="password"
            autoComplete="new-password"
            error={errors.confirmPassword?.message}
            {...register('confirmPassword')}
          />
          {passwordError ? <p className="m-0 text-label-sm text-error">{passwordError}</p> : null}
          {passwordMessage ? <p className="m-0 text-label-sm text-success">{passwordMessage}</p> : null}
          <div>
            <Button type="submit" disabled={isSubmitting}>
              <Icon name="lock_reset" className="text-[16px] leading-none" />
              {isSubmitting ? 'Changing…' : 'Change password'}
            </Button>
          </div>
        </form>

        <div className="flex flex-wrap gap-sm">
          <Button type="button" variant="outline" onClick={onSignOut} disabled={signingOut || loggingOutAll}>
            <Icon name="logout" className="text-[16px] leading-none" />
            {signingOut ? 'Signing out…' : 'Sign out'}
          </Button>
          <Button
            type="button"
            variant="destructive-soft"
            onClick={onLogoutAll}
            disabled={signingOut || loggingOutAll}
          >
            <Icon name="power_settings_new" className="text-[16px] leading-none" />
            {loggingOutAll ? 'Logging out…' : 'Log out of all devices'}
          </Button>
        </div>
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
