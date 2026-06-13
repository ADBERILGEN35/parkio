import { zodResolver } from '@hookform/resolvers/zod';
import { loginSchema, type LoginFormValues } from '@parkio/validation';
import { Button, ErrorMessage, Input, Surface } from '@parkio/ui';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link, useNavigate } from 'react-router-dom';
import { authApi } from '@/api';
import { describeAuthError } from '@/api/error-messages';
import { AuthBrand } from '@/pages/auth/AuthBrand';
import { useAuthStore } from '@/auth/store';

export function LoginPage() {
  const navigate = useNavigate();
  const setSession = useAuthStore((s) => s.setSession);
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
      setSession(result.accessToken, result.refreshToken, result.user);
      navigate('/map');
    } catch (error) {
      const friendly = describeAuthError(error, 'Login failed. Please try again.');
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
    <main className="flex min-h-screen flex-col items-center justify-center gap-lg bg-background px-md py-xl text-on-background">
      <AuthBrand />
      <Surface level="card" className="w-full max-w-md p-lg">
        <h1 className="m-0 text-headline-md text-on-surface">Welcome back</h1>
        <p className="m-0 mt-xs text-body-md text-on-surface-variant">
          Sign in to find and share parking.
        </p>
        <form onSubmit={onSubmit} className="mt-lg flex flex-col gap-md">
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
          {apiError ? <ErrorMessage message={apiError} traceId={traceId} /> : null}
          <Button type="submit" disabled={isSubmitting} className="w-full">
            {isSubmitting ? 'Signing in…' : 'Sign in'}
          </Button>
        </form>
        <p className="m-0 mt-md text-body-md text-on-surface-variant">
          No account?{' '}
          <Link to="/register" className="font-semibold text-primary hover:underline">
            Register
          </Link>
        </p>
      </Surface>
    </main>
  );
}
