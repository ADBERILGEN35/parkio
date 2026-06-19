import { Button, ErrorMessage, Icon, Input } from '@parkio/ui';
import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { authApi } from '@/api';
import { describeAuthError } from '@/api/error-messages';
import { AuthSplitLayout } from '@/pages/auth/AuthSplitLayout';
import { showError, showSuccess } from '@/lib/toast';

export function CheckEmailPage() {
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
      await authApi.resendVerification({ email: email.trim() });
      setMessage('Verification email sent. Please check your inbox.');
      showSuccess('Verification email sent. Please check your inbox.');
    } catch (error) {
      const friendly = describeAuthError(error, 'Could not resend verification email.');
      setApiError(friendly.message);
      setTraceId(friendly.traceId);
      showError(friendly.message);
    } finally {
      setIsSubmitting(false);
    }
  }

  return (
    <AuthSplitLayout title="Check your email" subtitle="Verify your address before signing in.">
      <div className="flex flex-col gap-md">
        <p className="m-0 text-body-md text-on-surface-variant">
          We sent a verification link to your email address. Open it to activate your Parkio
          account.
        </p>
        <Input
          label="Email"
          type="email"
          autoComplete="email"
          value={email}
          onChange={(event) => setEmail(event.target.value)}
        />
        {message ? <p className="m-0 text-body-md text-success">{message}</p> : null}
        {apiError ? <ErrorMessage message={apiError} traceId={traceId} /> : null}
        <Button type="button" disabled={isSubmitting || !email.trim()} onClick={resend} className="w-full">
          {isSubmitting ? 'Sending…' : 'Resend verification'}
          {isSubmitting ? null : <Icon name="send" className="text-[18px] leading-none" />}
        </Button>
        <p className="m-0 text-center text-body-md text-on-surface-variant">
          Already verified?{' '}
          <Link to="/login" className="font-semibold text-primary hover:underline">
            Sign in
          </Link>
        </p>
      </div>
    </AuthSplitLayout>
  );
}
