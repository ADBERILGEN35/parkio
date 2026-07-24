import { zodResolver } from '@hookform/resolvers/zod';
import { passwordRequirementState, type ResetPasswordFormValues } from '@parkio/validation';
import { Button, ErrorMessage, Icon, Input } from '@parkio/ui';
import { useMemo, useState } from 'react';
import { useForm } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { describeAuthError } from '@/api/error-messages';
import { useAppRuntime } from '@/app/AppRuntimeContext';
import { AuthSplitLayout } from '@/pages/auth/AuthSplitLayout';
import {
  createResetPasswordSchema,
  getPasswordRequirements,
} from '@/lib/validation/localized-schemas';
import { showError, showSuccess } from '@/lib/toast';

export function ResetPasswordPage() {
  const {
    authSession,
    sdk: { authApi },
  } = useAppRuntime();
  const { t } = useTranslation(['auth', 'common', 'validation', 'errors']);
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const token = searchParams.get('token') ?? '';
  const [apiError, setApiError] = useState<string | null>(null);
  const [traceId, setTraceId] = useState<string | undefined>();

  const schema = useMemo(() => createResetPasswordSchema(t), [t]);
  const requirements = useMemo(() => getPasswordRequirements(t), [t]);

  const {
    register,
    handleSubmit,
    setError,
    watch,
    formState: { errors, isSubmitting },
  } = useForm<ResetPasswordFormValues>({
    resolver: zodResolver(schema),
    defaultValues: { password: '', confirmPassword: '' },
  });
  const passwordValue = watch('password') ?? '';
  const passwordState = passwordRequirementState(passwordValue);

  const onSubmit = handleSubmit(async (values) => {
    if (!token) {
      const missing = t('auth:resetPassword.missingToken');
      setApiError(missing);
      showError(missing);
      return;
    }
    setApiError(null);
    setTraceId(undefined);
    try {
      await authApi.resetPassword({ token, newPassword: values.password });
      authSession.destroyLocalSession();
      showSuccess(t('auth:resetPassword.success'));
      navigate('/login?passwordReset=success', { replace: true });
    } catch (error) {
      const friendly = describeAuthError(error, t('errors:auth.resetPasswordFailed'), t);
      setApiError(friendly.message);
      setTraceId(friendly.traceId);
      showError(friendly.message);
      friendly.fieldErrors?.forEach((fe) => {
        if (fe.field === 'newPassword' || fe.field === 'password') {
          setError('password', { message: fe.message });
        }
      });
    }
  });

  return (
    <AuthSplitLayout
      title={t('auth:resetPassword.title')}
      subtitle={t('auth:resetPassword.subtitle')}
    >
      <form onSubmit={onSubmit} className="flex flex-col gap-md">
        <Input
          label={t('auth:resetPassword.password')}
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
                {met
                  ? t('auth:resetPassword.requirementMet')
                  : t('auth:resetPassword.requirementNeeded')}{' '}
                {requirement.label}
              </li>
            );
          })}
        </ul>
        <Input
          label={t('auth:resetPassword.confirmPassword')}
          type="password"
          autoComplete="new-password"
          error={errors.confirmPassword?.message}
          {...register('confirmPassword')}
        />

        {apiError ? <ErrorMessage message={apiError} traceId={traceId} /> : null}

        <Button type="submit" disabled={isSubmitting || !token} className="w-full">
          {isSubmitting ? t('auth:resetPassword.submitting') : t('auth:resetPassword.submit')}
          {isSubmitting ? null : <Icon name="lock_reset" className="text-[18px] leading-none" />}
        </Button>
      </form>

      <p className="m-0 mt-md text-center text-body-md text-on-surface-variant">
        {t('auth:resetPassword.needFreshLink')}{' '}
        <Link to="/forgot-password" className="font-semibold text-primary hover:underline">
          {t('auth:resetPassword.requestLink')}
        </Link>
      </p>
    </AuthSplitLayout>
  );
}
