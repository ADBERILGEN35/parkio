import { Button, ErrorMessage, Icon, Input } from '@parkio/ui';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useSearchParams } from 'react-router-dom';
import { describeAuthError } from '@/api/error-messages';
import { useParkioSdk } from '@/app/AppRuntimeContext';
import { AuthSplitLayout } from '@/pages/auth/AuthSplitLayout';
import { showError, showSuccess } from '@/lib/toast';

export function CheckEmailPage() {
  const { authApi } = useParkioSdk();
  const { t, i18n } = useTranslation(['auth', 'common', 'errors']);
  const [searchParams] = useSearchParams();
  const [email, setEmail] = useState(searchParams.get('email') ?? '');
  const [message, setMessage] = useState<string | null>(null);
  const [apiError, setApiError] = useState<string | null>(null);
  const [traceId, setTraceId] = useState<string | undefined>();
  const [isSubmitting, setIsSubmitting] = useState(false);

  async function resend() {
    if (!email.trim()) return;
    setIsSubmitting(true);
    setMessage(null);
    setApiError(null);
    setTraceId(undefined);
    try {
      await authApi.resendVerification({
        email: email.trim(),
        locale: i18n.language === 'en' ? 'en' : 'tr',
      });
      const resent = t('auth:checkEmail.resent');
      setMessage(resent);
      showSuccess(resent);
    } catch (error) {
      const friendly = describeAuthError(error, t('errors:auth.resendFailed'), t);
      setApiError(friendly.message);
      setTraceId(friendly.traceId);
      showError(friendly.message);
    } finally {
      setIsSubmitting(false);
    }
  }

  return (
    <AuthSplitLayout title={t('auth:checkEmail.title')} subtitle={t('auth:checkEmail.subtitle')}>
      <form
        className="flex flex-col gap-md"
        onSubmit={(event) => {
          event.preventDefault();
          void resend();
        }}
      >
        <p className="m-0 text-body-md text-on-surface-variant">{t('auth:checkEmail.body')}</p>
        <Input
          label={t('auth:checkEmail.email')}
          type="email"
          autoComplete="email"
          value={email}
          onChange={(event) => setEmail(event.target.value)}
        />
        {message ? <p className="m-0 text-body-md text-success">{message}</p> : null}
        {apiError ? <ErrorMessage message={apiError} traceId={traceId} /> : null}
        <Button type="submit" disabled={isSubmitting || !email.trim()} className="w-full">
          {isSubmitting ? t('auth:checkEmail.sending') : t('auth:checkEmail.resend')}
          {isSubmitting ? null : <Icon name="send" className="text-[18px] leading-none" />}
        </Button>
        <p className="m-0 text-center text-body-md text-on-surface-variant">
          {t('auth:checkEmail.alreadyVerified')}{' '}
          <Link to="/login" className="font-semibold text-primary hover:underline">
            {t('auth:checkEmail.signInLink')}
          </Link>
        </p>
      </form>
    </AuthSplitLayout>
  );
}
