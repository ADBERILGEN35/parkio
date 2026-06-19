import { zodResolver } from '@hookform/resolvers/zod';
import { forgotPasswordSchema, type ForgotPasswordFormValues } from '@parkio/validation';
import { Button, ErrorMessage, Icon, Input } from '@parkio/ui';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link } from 'react-router-dom';
import { authApi } from '@/api';
import { describeAuthError } from '@/api/error-messages';
import { AuthSplitLayout } from '@/pages/auth/AuthSplitLayout';
import { showError, showSuccess } from '@/lib/toast';

const GENERIC_SUCCESS = 'If an account exists, we sent password reset instructions.';

export function ForgotPasswordPage() {
  const [message, setMessage] = useState<string | null>(null);
  const [apiError, setApiError] = useState<string | null>(null);
  const [traceId, setTraceId] = useState<string | undefined>();

  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<ForgotPasswordFormValues>({ resolver: zodResolver(forgotPasswordSchema) });

  const onSubmit = handleSubmit(async (values) => {
    setApiError(null);
    setTraceId(undefined);
    try {
      await authApi.forgotPassword({ email: values.email });
      setMessage(GENERIC_SUCCESS);
      showSuccess(GENERIC_SUCCESS);
    } catch (error) {
      const friendly = describeAuthError(error, 'We could not process the request. Please try again.');
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
    <AuthSplitLayout title="Reset your password" subtitle="Enter your email to receive reset instructions.">
      <form onSubmit={onSubmit} className="flex flex-col gap-md">
        <Input
          label="Email"
          type="email"
          autoComplete="email"
          error={errors.email?.message}
          {...register('email')}
        />

        {message ? <p className="m-0 rounded-lg bg-success/10 p-sm text-body-md text-success">{message}</p> : null}
        {apiError ? <ErrorMessage message={apiError} traceId={traceId} /> : null}

        <Button type="submit" disabled={isSubmitting} className="w-full">
          {isSubmitting ? 'Sending…' : 'Send instructions'}
          {isSubmitting ? null : <Icon name="mail" className="text-[18px] leading-none" />}
        </Button>
      </form>

      <p className="m-0 mt-md text-center text-body-md text-on-surface-variant">
        Remembered it?{' '}
        <Link to="/login" className="font-semibold text-primary hover:underline">
          Sign in
        </Link>
      </p>
    </AuthSplitLayout>
  );
}
