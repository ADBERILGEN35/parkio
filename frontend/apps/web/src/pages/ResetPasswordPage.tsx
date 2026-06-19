import { zodResolver } from '@hookform/resolvers/zod';
import {
  passwordRequirementState,
  passwordRequirements,
  resetPasswordSchema,
  type ResetPasswordFormValues,
} from '@parkio/validation';
import { Button, ErrorMessage, Icon, Input } from '@parkio/ui';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { authApi } from '@/api';
import { describeAuthError } from '@/api/error-messages';
import { useAuthStore } from '@/auth/store';
import { AuthSplitLayout } from '@/pages/auth/AuthSplitLayout';
import { showError, showSuccess } from '@/lib/toast';

export function ResetPasswordPage() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const clearSession = useAuthStore((s) => s.clearSession);
  const token = searchParams.get('token') ?? '';
  const [apiError, setApiError] = useState<string | null>(null);
  const [traceId, setTraceId] = useState<string | undefined>();

  const {
    register,
    handleSubmit,
    setError,
    watch,
    formState: { errors, isSubmitting },
  } = useForm<ResetPasswordFormValues>({
    resolver: zodResolver(resetPasswordSchema),
    defaultValues: { password: '', confirmPassword: '' },
  });
  const passwordValue = watch('password') ?? '';
  const passwordState = passwordRequirementState(passwordValue);

  const onSubmit = handleSubmit(async (values) => {
    if (!token) {
      setApiError('Password reset link is missing a token.');
      showError('Password reset link is missing a token.');
      return;
    }
    setApiError(null);
    setTraceId(undefined);
    try {
      await authApi.resetPassword({ token, newPassword: values.password });
      clearSession();
      showSuccess('Password reset. Sign in with your new password.');
      navigate('/login?passwordReset=success', { replace: true });
    } catch (error) {
      const friendly = describeAuthError(error, 'Password reset failed. Request a new link and try again.');
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
    <AuthSplitLayout title="Choose a new password" subtitle="Use a strong password you do not use elsewhere.">
      <form onSubmit={onSubmit} className="flex flex-col gap-md">
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
          label="Confirm password"
          type="password"
          autoComplete="new-password"
          error={errors.confirmPassword?.message}
          {...register('confirmPassword')}
        />

        {apiError ? <ErrorMessage message={apiError} traceId={traceId} /> : null}

        <Button type="submit" disabled={isSubmitting || !token} className="w-full">
          {isSubmitting ? 'Resetting…' : 'Reset password'}
          {isSubmitting ? null : <Icon name="lock_reset" className="text-[18px] leading-none" />}
        </Button>
      </form>

      <p className="m-0 mt-md text-center text-body-md text-on-surface-variant">
        Need a fresh link?{' '}
        <Link to="/forgot-password" className="font-semibold text-primary hover:underline">
          Request instructions
        </Link>
      </p>
    </AuthSplitLayout>
  );
}
