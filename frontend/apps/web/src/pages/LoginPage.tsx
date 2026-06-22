import { zodResolver } from '@hookform/resolvers/zod';
import { loginSchema, type LoginFormValues } from '@parkio/validation';
import { Button, ErrorMessage, Icon, Input } from '@parkio/ui';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link, useNavigate } from 'react-router-dom';
import { authApi } from '@/api';
import { describeAuthError } from '@/api/error-messages';
import { AuthSplitLayout } from '@/pages/auth/AuthSplitLayout';
import { getPendingProfile } from '@/auth/pendingProfile';
import { useAuthStore } from '@/auth/store';
import { showError, showSuccess } from '@/lib/toast';

export function LoginPage() {
  const navigate = useNavigate();
  const setSession = useAuthStore((s) => s.setSession);
  const beginProvisioning = useAuthStore((s) => s.beginProvisioning);
  const [apiError, setApiError] = useState<string | null>(null);
  const [traceId, setTraceId] = useState<string | undefined>();

  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<LoginFormValues>({ resolver: zodResolver(loginSchema) });

  const onSubmit = handleSubmit(async (values) => {
    setApiError(null);
    setTraceId(undefined);
    try {
      const result = await authApi.login(values);
      if (!result.accessToken) {
        throw new Error('Login response did not include an access token.');
      }
      setSession(result.accessToken, result.user);
      showSuccess('Signed in successfully.');
      if (getPendingProfile()) {
        beginProvisioning();
        navigate('/preparing');
      } else {
        navigate('/map');
      }
    } catch (error) {
      const friendly = describeAuthError(error, 'Login failed. Please try again.');
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
    <AuthSplitLayout title="Welcome back" subtitle="Sign in to find and share parking.">
      <form onSubmit={onSubmit} className="flex flex-col gap-md">
        <Input
          label="Email"
          type="email"
          autoComplete="email"
          error={errors.email?.message}
          {...register('email')}
        />
        <Input
          label="Password"
          type="password"
          autoComplete="current-password"
          error={errors.password?.message}
          {...register('password')}
        />

        <div className="flex items-center justify-between gap-sm">
          <p className="m-0 text-label-md text-on-surface-variant">
            Sessions stay active securely with an HttpOnly refresh cookie.
          </p>
          <Link to="/forgot-password" className="text-label-md font-semibold text-primary hover:underline">
            Forgot password?
          </Link>
        </div>

        {apiError ? <ErrorMessage message={apiError} traceId={traceId} /> : null}

        <Button type="submit" disabled={isSubmitting} className="w-full">
          {isSubmitting ? 'Signing in…' : 'Sign in'}
          {isSubmitting ? null : <Icon name="arrow_forward" className="text-[18px] leading-none" />}
        </Button>
      </form>

      <p className="m-0 mt-md text-center text-body-md text-on-surface-variant">
        No account?{' '}
        <Link to="/register" className="font-semibold text-primary hover:underline">
          Register
        </Link>
      </p>
    </AuthSplitLayout>
  );
}
