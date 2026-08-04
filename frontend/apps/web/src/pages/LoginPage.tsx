import { zodResolver } from '@hookform/resolvers/zod';
import type { LoginFormValues } from '@parkio/validation';
import { Button, ErrorMessage, Icon, Input } from '@parkio/ui';
import { useMemo, useState } from 'react';
import { useForm } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { describeAuthError } from '@/api/error-messages';
import { useParkioSdk } from '@/app/AppRuntimeContext';
import { AuthSplitLayout } from '@/pages/auth/AuthSplitLayout';
import { getPendingProfile } from '@/auth/pendingProfile';
import {
  AUTH_RETURN_QUERY_PARAM,
  sanitizeInternalRedirect,
} from '@/auth/redirect';
import { useAuthStore } from '@/auth/store';
import { createLoginSchema } from '@/lib/validation/localized-schemas';
import { showError, showSuccess } from '@/lib/toast';

export function LoginPage() {
  const { authApi } = useParkioSdk();
  const { t } = useTranslation(['auth', 'common', 'validation', 'errors']);
  const location = useLocation();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const passwordResetSuccess = searchParams.get('passwordReset') === 'success';
  const setSession = useAuthStore((s) => s.setSession);
  const beginProvisioning = useAuthStore((s) => s.beginProvisioning);
  const [apiError, setApiError] = useState<string | null>(null);
  const [traceId, setTraceId] = useState<string | undefined>();

  const schema = useMemo(() => createLoginSchema(t), [t]);
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<LoginFormValues>({ resolver: zodResolver(schema) });

  const onSubmit = handleSubmit(async (values) => {
    setApiError(null);
    setTraceId(undefined);
    try {
      const result = await authApi.login(values);
      if (!result.accessToken) {
        throw new Error(t('auth:login.missingToken'));
      }
      setSession(result.accessToken, result.user);
      showSuccess(t('auth:login.success'));
      if (getPendingProfile()) {
        beginProvisioning();
        navigate('/preparing');
      } else {
        const from = (location.state as { from?: unknown } | null)?.from;
        navigate(
          sanitizeInternalRedirect(searchParams.get(AUTH_RETURN_QUERY_PARAM), from),
        );
      }
    } catch (error) {
      const friendly = describeAuthError(error, t('errors:auth.loginFailed'), t);
      setApiError(friendly.message);
      setTraceId(friendly.traceId);
      showError(friendly.message);
      friendly.fieldErrors?.forEach((fe) => {
        if (fe.field === 'email' || fe.field === 'password') {
          setError(fe.field, { message: fe.message });
        }
      });
    }
  });

  return (
    <AuthSplitLayout title={t('auth:login.title')} subtitle={t('auth:login.subtitle')}>
      {passwordResetSuccess ? (
        <p className="mb-md rounded-2xl bg-success/10 px-md py-sm text-body-md text-success" role="status">
          {t('auth:login.passwordResetSuccess')}
        </p>
      ) : null}
      <form onSubmit={onSubmit} className="flex flex-col gap-md">
        <Input
          label={t('auth:login.email')}
          type="email"
          autoComplete="email"
          error={errors.email?.message}
          {...register('email')}
        />
        <Input
          label={t('auth:login.password')}
          type="password"
          autoComplete="current-password"
          error={errors.password?.message}
          {...register('password')}
        />

        <div className="flex items-center justify-between gap-sm">
          <p className="m-0 text-label-md text-on-surface-variant">{t('auth:login.sessionHint')}</p>
          <Link to="/forgot-password" className="text-label-md font-semibold text-primary hover:underline">
            {t('auth:login.forgotPassword')}
          </Link>
        </div>

        {apiError ? <ErrorMessage message={apiError} traceId={traceId} /> : null}

        <Button type="submit" disabled={isSubmitting} className="w-full">
          {isSubmitting ? t('auth:login.submitting') : t('auth:login.submit')}
          {isSubmitting ? null : <Icon name="arrow_forward" className="text-[18px] leading-none" />}
        </Button>
      </form>

      <p className="m-0 mt-md text-center text-body-md text-on-surface-variant">
        {t('auth:login.noAccount')}{' '}
        <Link to="/register" className="font-semibold text-primary hover:underline">
          {t('auth:login.registerLink')}
        </Link>
      </p>
    </AuthSplitLayout>
  );
}
