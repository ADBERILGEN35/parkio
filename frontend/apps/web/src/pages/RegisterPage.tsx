import { zodResolver } from '@hookform/resolvers/zod';
import { registerSchema, type RegisterFormValues } from '@parkio/validation';
import { Button, Card, ErrorMessage, Input, PageShell } from '@parkio/ui';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link, useNavigate } from 'react-router-dom';
import { authApi } from '@/api';
import { describeAuthError } from '@/api/error-messages';
import { useAuthStore } from '@/auth/store';

export function RegisterPage() {
  const navigate = useNavigate();
  const setSession = useAuthStore((s) => s.setSession);
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
      navigate('/map');
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
    <PageShell title="Create account">
      <Card title="Register">
        <form onSubmit={onSubmit} style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
          <Input label="Email" type="email" autoComplete="email" error={errors.email?.message} {...register('email')} />
          <Input
            label="Password"
            type="password"
            autoComplete="new-password"
            error={errors.password?.message}
            {...register('password')}
          />
          {apiError ? <ErrorMessage message={apiError} traceId={traceId} /> : null}
          <Button type="submit" disabled={isSubmitting}>
            {isSubmitting ? 'Creating…' : 'Create account'}
          </Button>
        </form>
        <p style={{ marginTop: '1rem', fontSize: '0.875rem' }}>
          Already have an account? <Link to="/login">Sign in</Link>
        </p>
      </Card>
    </PageShell>
  );
}
