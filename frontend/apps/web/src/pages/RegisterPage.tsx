import { zodResolver } from '@hookform/resolvers/zod';
import { registerSchema, type RegisterFormValues } from '@parkio/validation';
import { Button, ErrorMessage, Icon, Input } from '@parkio/ui';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link, useNavigate } from 'react-router-dom';
import { authApi } from '@/api';
import { describeAuthError } from '@/api/error-messages';
import { AuthDivider, AuthSplitLayout, GoogleButton } from '@/pages/auth/AuthSplitLayout';
import { useAuthStore } from '@/auth/store';

export function RegisterPage() {
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
  } = useForm<RegisterFormValues>({ resolver: zodResolver(registerSchema) });

  const onSubmit = handleSubmit(async (values) => {
    setApiError(null);
    setTraceId(undefined);
    try {
      const result = await authApi.register(values);
      setSession(result.accessToken, result.refreshToken, result.user);
      // The profile/status is provisioned asynchronously, so protected calls can
      // briefly 403 ACCOUNT_NOT_ACTIVE. Enter the grace window and hand off to the
      // preparing screen, which polls readiness before forwarding to /map.
      beginProvisioning();
      navigate('/preparing');
    } catch (error) {
      const friendly = describeAuthError(error, 'Registration failed. Please try again.');
      setApiError(friendly.message);
      setTraceId(friendly.traceId);
      friendly.fieldErrors?.forEach((fe) => {
        if (fe.field === 'email' || fe.field === 'password') {
          setError(fe.field, { message: fe.message });
        }
      });
    }
  });

  return (
    <AuthSplitLayout
      title="Create your account"
      subtitle="Join the community and start sharing curb space."
    >
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
          autoComplete="new-password"
          error={errors.password?.message}
          {...register('password')}
        />

        {apiError ? <ErrorMessage message={apiError} traceId={traceId} /> : null}

        <Button type="submit" disabled={isSubmitting} className="w-full">
          {isSubmitting ? 'Creating…' : 'Create account'}
          {isSubmitting ? null : <Icon name="arrow_forward" className="text-[18px] leading-none" />}
        </Button>
      </form>

      <AuthDivider />
      <GoogleButton label="Continue with Google" />

      <p className="m-0 mt-md text-center text-body-md text-on-surface-variant">
        Already have an account?{' '}
        <Link to="/login" className="font-semibold text-primary hover:underline">
          Sign in
        </Link>
      </p>
    </AuthSplitLayout>
  );
}
