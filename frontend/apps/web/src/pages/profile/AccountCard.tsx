import { zodResolver } from '@hookform/resolvers/zod';
import { passwordRequirementState, type ChangePasswordFormValues } from '@parkio/validation';
import { Button, Icon, Input, SoftBadge } from '@parkio/ui';
import { useQuery } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { useForm } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { describeAuthError } from '@/api/error-messages';
import { useAppRuntime } from '@/app/AppRuntimeContext';
import { performLogout } from '@/auth/logout';
import { useAuthStore } from '@/auth/store';
import { SettingsSectionCard } from '@/components/product/SettingsSectionCard';
import { enumLabel } from '@/lib/format';
import {
  createChangePasswordSchema,
  getPasswordRequirements,
} from '@/lib/validation/localized-schemas';
import { showError, showSuccess, showWarning } from '@/lib/toast';
import { accountStatusTone } from './accountVisuals';

/**
 * Account summary + settings: email, status, roles and (best-effort) the
 * platform auth user id, plus the sign-out action. Identity comes from the
 * auth session (so sign-out never depends on a network call); `authUserId`
 * is enriched from the profile query when available.
 */
export function AccountCard() {
  const {
    authSession,
    sdk: { authApi, usersApi },
  } = useAppRuntime();
  const { t } = useTranslation(['settings', 'common', 'validation']);
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);
  const roles = useAuthStore((s) => s.roles);
  const status = useAuthStore((s) => s.status);
  const [signingOut, setSigningOut] = useState(false);
  const [loggingOutAll, setLoggingOutAll] = useState(false);
  const [passwordMessage, setPasswordMessage] = useState<string | null>(null);
  const [passwordError, setPasswordError] = useState<string | null>(null);

  const passwordSchema = useMemo(() => createChangePasswordSchema(t), [t]);
  const requirements = useMemo(() => getPasswordRequirements(t), [t]);

  // Best-effort enrichment only — already cached by ImpactHero, never blocks sign-out.
  const profile = useQuery({ queryKey: ['me', 'profile'], queryFn: usersApi.getMyProfile });
  const {
    register,
    handleSubmit,
    reset,
    watch,
    formState: { errors, isSubmitting },
  } = useForm<ChangePasswordFormValues>({
    resolver: zodResolver(passwordSchema),
    defaultValues: { currentPassword: '', password: '', confirmPassword: '' },
  });
  const passwordValue = watch('password') ?? '';
  const passwordState = passwordRequirementState(passwordValue);

  const onSignOut = async () => {
    setSigningOut(true);
    try {
      await performLogout(authSession);
      showSuccess(t('account.signedOut'));
      navigate('/login', { replace: true });
    } finally {
      setSigningOut(false);
    }
  };

  const onLogoutAll = async () => {
    if (!window.confirm(t('account.logoutAllConfirm'))) {
      return;
    }
    setLoggingOutAll(true);
    try {
      await authApi.logoutAll();
    } catch {
      showWarning(t('account.logoutAllOffline'));
      // The local session is still cleared so this browser cannot keep using a stale token.
    } finally {
      authSession.destroyLocalSession();
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
      setPasswordMessage(t('account.passwordChanged'));
      showSuccess(t('account.passwordChanged'));
      authSession.destroyLocalSession();
      navigate('/login', { replace: true });
    } catch (error) {
      const friendly = describeAuthError(error, t('account.passwordFailed'));
      setPasswordError(friendly.message);
      showError(friendly.message);
    }
  });

  return (
    <SettingsSectionCard
      title={t('account.title')}
      icon="manage_accounts"
      description={t('account.description')}
    >
      <dl className="m-0 flex flex-col gap-md">
        <Row label={t('account.email')} value={user?.email ?? profile.data?.email ?? '—'} />
        <div className="flex flex-col gap-xs">
          <dt className="text-label-sm font-medium text-on-surface-variant">{t('account.status')}</dt>
          <dd className="m-0">
            {status ? (
              <SoftBadge tone={accountStatusTone(status)} icon="account_circle">
                {enumLabel(status, t)}
              </SoftBadge>
            ) : (
              <span className="text-body-md text-on-surface-variant">—</span>
            )}
          </dd>
        </div>
        <div className="flex flex-col gap-xs">
          <dt className="text-label-sm font-medium text-on-surface-variant">{t('account.roles')}</dt>
          <dd className="m-0 flex flex-wrap gap-xs">
            {roles.length > 0 ? (
              roles.map((role) => (
                <SoftBadge key={role} tone="primary" icon="badge">
                  {enumLabel(role, t)}
                </SoftBadge>
              ))
            ) : (
              <span className="text-body-md text-on-surface-variant">—</span>
            )}
          </dd>
        </div>
        {profile.data?.authUserId ? (
          <div className="flex flex-col gap-xs">
            <dt className="text-label-sm font-medium text-on-surface-variant">{t('account.authUserId')}</dt>
            <dd className="m-0 break-all font-mono text-label-sm text-on-surface-variant">
              {profile.data.authUserId}
            </dd>
          </div>
        ) : null}
      </dl>

      <div className="mt-lg border-t border-outline-variant/30 pt-md">
        <form onSubmit={onChangePassword} className="mb-lg flex flex-col gap-sm">
          <h3 className="m-0 text-title-lg text-on-surface">{t('account.changePassword')}</h3>
          <p className="m-0 text-label-sm text-on-surface-variant">
            {t('account.changePasswordHelp')}
          </p>
          <Input
            label={t('account.currentPassword')}
            type="password"
            autoComplete="current-password"
            error={errors.currentPassword?.message}
            {...register('currentPassword')}
          />
          <Input
            label={t('account.newPassword')}
            type="password"
            autoComplete="new-password"
            error={errors.password?.message}
            {...register('password')}
          />
          <ul className="m-0 grid list-none gap-1 p-0 text-label-sm text-on-surface-variant">
            {requirements.map((requirement) => {
              const met = passwordState[requirement.id];
              return (
                <li key={requirement.id} className={met ? 'text-success' : 'text-on-surface-variant'}>
                  {met ? t('account.passwordMet') : t('account.passwordNeeded')} {requirement.label}
                </li>
              );
            })}
          </ul>
          <Input
            label={t('account.confirmPassword')}
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
              {isSubmitting ? t('account.changing') : t('account.changePassword')}
            </Button>
          </div>
        </form>

        <div className="rounded-2xl bg-surface-container p-md">
          <h3 className="m-0 flex items-center gap-xs text-title-lg text-on-surface">
            <Icon name="shield_lock" className="text-[18px] leading-none text-primary" />
            {t('account.sessionControls')}
          </h3>
          <p className="m-0 mt-xs text-label-sm text-on-surface-variant">
            {t('account.sessionHelp')}
          </p>
          <div className="mt-md flex flex-wrap gap-sm">
            <Button type="button" variant="outline" onClick={onSignOut} disabled={signingOut || loggingOutAll}>
              <Icon name="logout" className="text-[16px] leading-none" />
              {signingOut ? t('account.signingOut') : t('actions.signOut', { ns: 'common' })}
            </Button>
            <Button
              type="button"
              variant="destructive-soft"
              onClick={onLogoutAll}
              disabled={signingOut || loggingOutAll}
            >
              <Icon name="power_settings_new" className="text-[16px] leading-none" />
              {loggingOutAll ? t('account.loggingOut') : t('account.logoutAll')}
            </Button>
          </div>
        </div>
      </div>
    </SettingsSectionCard>
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
