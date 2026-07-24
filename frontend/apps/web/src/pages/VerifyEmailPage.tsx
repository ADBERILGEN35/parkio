import { Button, ErrorMessage, Icon } from '@parkio/ui';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { describeAuthError } from '@/api/error-messages';
import { useParkioSdk } from '@/app/AppRuntimeContext';
import { AuthSplitLayout } from '@/pages/auth/AuthSplitLayout';
import { showError, showSuccess } from '@/lib/toast';

type VerifyState = 'verifying' | 'success' | 'error';

export function VerifyEmailPage() {
  const { authApi } = useParkioSdk();
  const { t } = useTranslation(['auth', 'common', 'errors']);
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const [state, setState] = useState<VerifyState>('verifying');
  const [apiError, setApiError] = useState<string | null>(null);
  const [traceId, setTraceId] = useState<string | undefined>();

  useEffect(() => {
    const token = searchParams.get('token');
    if (!token) {
      setState('error');
      setApiError(t('auth:verifyEmail.invalidLink'));
      return;
    }

    let cancelled = false;
    authApi
      .verifyEmail({ token })
      .then(() => {
        if (!cancelled) {
          setState('success');
          showSuccess(t('auth:verifyEmail.successToast'));
        }
      })
      .catch((error) => {
        if (cancelled) return;
        const friendly = describeAuthError(error, t('errors:auth.verifyFailed'), t);
        setApiError(friendly.message);
        setTraceId(friendly.traceId);
        setState('error');
        showError(friendly.message);
      });

    return () => {
      cancelled = true;
    };
  }, [authApi, searchParams, t]);

  return (
    <AuthSplitLayout
      title={state === 'success' ? t('auth:verifyEmail.titleSuccess') : t('auth:verifyEmail.title')}
      subtitle={
        state === 'success' ? t('auth:verifyEmail.subtitleSuccess') : t('auth:verifyEmail.subtitle')
      }
    >
      <div className="flex flex-col gap-md">
        {state === 'verifying' ? (
          <p
            className="m-0 flex items-center gap-sm text-body-md text-on-surface-variant"
            role="status"
            aria-live="polite"
          >
            <Icon name="progress_activity" className="animate-spin text-[18px] leading-none text-primary" />
            {t('auth:verifyEmail.verifying')}
          </p>
        ) : null}
        {state === 'success' ? (
          <>
            <p className="m-0 text-body-md text-on-surface-variant">{t('auth:verifyEmail.successBody')}</p>
            <Button type="button" onClick={() => navigate('/login')} className="w-full">
              {t('auth:verifyEmail.signIn')}
              <Icon name="arrow_forward" className="text-[18px] leading-none" />
            </Button>
          </>
        ) : null}
        {state === 'error' ? (
          <>
            <ErrorMessage
              message={apiError ?? t('auth:verifyEmail.invalidLink')}
              traceId={traceId}
            />
            <Button type="button" onClick={() => navigate('/check-email')} className="w-full">
              {t('auth:verifyEmail.requestNewLink')}
            </Button>
          </>
        ) : null}
      </div>
    </AuthSplitLayout>
  );
}
