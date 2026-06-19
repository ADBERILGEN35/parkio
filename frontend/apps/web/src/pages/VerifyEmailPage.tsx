import { Button, ErrorMessage, Icon } from '@parkio/ui';
import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { authApi } from '@/api';
import { describeAuthError } from '@/api/error-messages';
import { AuthSplitLayout } from '@/pages/auth/AuthSplitLayout';
import { showError, showSuccess } from '@/lib/toast';

type VerifyState = 'verifying' | 'success' | 'error';

export function VerifyEmailPage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const [state, setState] = useState<VerifyState>('verifying');
  const [apiError, setApiError] = useState<string | null>(null);
  const [traceId, setTraceId] = useState<string | undefined>();

  useEffect(() => {
    const token = searchParams.get('token');
    if (!token) {
      setState('error');
      setApiError('Verification link is invalid or expired.');
      return;
    }

    let cancelled = false;
    authApi
      .verifyEmail({ token })
      .then(() => {
        if (!cancelled) {
          setState('success');
          showSuccess('Email verified. You can sign in now.');
        }
      })
      .catch((error) => {
        if (cancelled) return;
        const friendly = describeAuthError(error, 'Verification link is invalid or expired.');
        setApiError(friendly.message);
        setTraceId(friendly.traceId);
        setState('error');
        showError(friendly.message);
      });

    return () => {
      cancelled = true;
    };
  }, [searchParams]);

  return (
    <AuthSplitLayout
      title={state === 'success' ? 'Email verified' : 'Verify your email'}
      subtitle={state === 'success' ? 'Your account is ready for sign in.' : 'Checking your link.'}
    >
      <div className="flex flex-col gap-md">
        {state === 'verifying' ? (
          <p className="m-0 text-body-md text-on-surface-variant">Verifying your email…</p>
        ) : null}
        {state === 'success' ? (
          <>
            <p className="m-0 text-body-md text-on-surface-variant">
              Your email is verified. Sign in to finish setting up your profile.
            </p>
            <Button type="button" onClick={() => navigate('/login')} className="w-full">
              Sign in
              <Icon name="arrow_forward" className="text-[18px] leading-none" />
            </Button>
          </>
        ) : null}
        {state === 'error' ? (
          <>
            <ErrorMessage message={apiError ?? 'Verification link is invalid or expired.'} traceId={traceId} />
            <Button type="button" onClick={() => navigate('/check-email')} className="w-full">
              Request a new link
            </Button>
          </>
        ) : null}
      </div>
    </AuthSplitLayout>
  );
}
