import { zodResolver } from '@hookform/resolvers/zod';
import type { ForgotPasswordFormValues } from '@parkio/validation';
import { Button, ErrorMessage, Icon, Input } from '@parkio/ui';
import { useMemo, useState } from 'react';
import { useForm } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { describeAuthError } from '@/api/error-messages';
import { useParkioSdk } from '@/app/AppRuntimeContext';
import { AuthSplitLayout } from '@/pages/auth/AuthSplitLayout';
import { createForgotPasswordSchema } from '@/lib/validation/localized-schemas';
import { showError, showSuccess } from '@/lib/toast';

export function ForgotPasswordPage() {
  const { authApi } = useParkioSdk();
  const { t, i18n } = useTranslation(['auth', 'common', 'validation', 'errors']);
  const [message, setMessage] = useState<string | null>(null);
  const [apiError, setApiError] = useState<string | null>(null);
  const [traceId, setTraceId] = useState<string | undefined>();

  const schema = useMemo(() => createForgotPasswordSchema(t), [t]);
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<ForgotPasswordFormValues>({ resolver: zodResolver(schema) });

  const onSubmit = handleSubmit(async (values) => {
    setApiError(null);
    setTraceId(undefined);
    try {
      await authApi.forgotPassword({
        email: values.email,
        locale: i18n.language === 'en' ? 'en' : 'tr',
      });
      const success = t('auth:forgotPassword.success');
      setMessage(success);
      showSuccess(success);
    } catch (error) {
      const friendly = describeAuthError(error, t('errors:auth.forgotPasswordFailed'), t);
      setApiError(friendly.message);
      setTraceId(friendly.traceId);
      showError(friendly.message);
      friendly.fieldErrors?.forEach((fe) => {
        if (fe.field === 'email') {
          setError('email', { message: fe.message });
        }
      });
    }
  });

  return (
    <AuthSplitLayout
      title={t('auth:forgotPassword.title')}
      subtitle={t('auth:forgotPassword.subtitle')}
    >
      <form onSubmit={onSubmit} className="flex flex-col gap-md">
        <Input
          label={t('auth:forgotPassword.email')}
          type="email"
          autoComplete="email"
          error={errors.email?.message}
          {...register('email')}
        />

        {message ? <p className="m-0 rounded-lg bg-success/10 p-sm text-body-md text-success">{message}</p> : null}
        {apiError ? <ErrorMessage message={apiError} traceId={traceId} /> : null}

        <Button type="submit" disabled={isSubmitting} className="w-full">
          {isSubmitting ? t('auth:forgotPassword.submitting') : t('auth:forgotPassword.submit')}
          {isSubmitting ? null : <Icon name="mail" className="text-[18px] leading-none" />}
        </Button>
      </form>

      <p className="m-0 mt-md text-center text-body-md text-on-surface-variant">
        {t('auth:forgotPassword.remembered')}{' '}
        <Link to="/login" className="font-semibold text-primary hover:underline">
          {t('auth:forgotPassword.signInLink')}
        </Link>
      </p>
    </AuthSplitLayout>
  );
}
