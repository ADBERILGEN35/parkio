import { zodResolver } from '@hookform/resolvers/zod';
import {
  passwordRequirementState,
  passwordRequirements,
  registerProfileSchema,
  type RegisterProfileFormValues,
} from '@parkio/validation';
import { Button, ErrorMessage, Icon, Input } from '@parkio/ui';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link, useNavigate } from 'react-router-dom';
import { authApi } from '@/api';
import { describeAuthError } from '@/api/error-messages';
import { AuthDivider, AuthSplitLayout, GoogleButton } from '@/pages/auth/AuthSplitLayout';
import { setPendingProfile } from '@/auth/pendingProfile';
import { showError, showSuccess } from '@/lib/toast';

export function RegisterPage() {
  const navigate = useNavigate();
  const [apiError, setApiError] = useState<string | null>(null);
  const [traceId, setTraceId] = useState<string | undefined>();

  const {
    register,
    handleSubmit,
    setError,
    watch,
    formState: { errors, isSubmitting },
  } = useForm<RegisterProfileFormValues>({
    resolver: zodResolver(registerProfileSchema),
    defaultValues: {
      displayName: '',
      email: '',
      phoneNumber: '',
      password: '',
      confirmPassword: '',
      termsAccepted: false,
    },
  });
  const passwordValue = watch('password') ?? '';
  const passwordState = passwordRequirementState(passwordValue);

  const onSubmit = handleSubmit(async (values) => {
    setApiError(null);
    setTraceId(undefined);
    try {
      // Only email + password are accepted by the backend register endpoint.
      await authApi.register({ email: values.email, password: values.password });
      // Stash the captured profile fields; the preparing screen persists them via
      // PATCH /users/me once the profile has provisioned.
      setPendingProfile({
        displayName: values.displayName.trim(),
        phoneNumber: values.phoneNumber?.trim() || undefined,
      });
      showSuccess('Account created. Check your email to verify it.');
      navigate(`/check-email?email=${encodeURIComponent(values.email.trim())}`);
    } catch (error) {
      const friendly = describeAuthError(error, 'Registration failed. Please try again.');
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
    <AuthSplitLayout
      title="Create your account"
      subtitle="Join the community and start sharing curb space."
    >
      <form onSubmit={onSubmit} className="flex flex-col gap-md">
        <Input
          label="Full name"
          autoComplete="name"
          error={errors.displayName?.message}
          {...register('displayName')}
        />
        <Input
          label="Email"
          type="email"
          autoComplete="email"
          error={errors.email?.message}
          {...register('email')}
        />
        <div className="flex flex-col gap-xs">
          <Input
            label="Phone number (optional)"
            type="tel"
            autoComplete="tel"
            error={errors.phoneNumber?.message}
            {...register('phoneNumber')}
          />
          <p className="m-0 text-label-sm text-on-surface-variant">
            We&apos;ll use this later for account recovery and verification.
          </p>
        </div>
        <Input
          label="Password"
          type="password"
          autoComplete="new-password"
          error={errors.password?.message}
          {...register('password')}
        />
        <ul className="m-0 grid list-none gap-1 p-0 text-label-sm text-on-surface-variant">
          {passwordRequirements.map((requirement) => {
            const met = passwordState[requirement.id];
            return (
              <li
                key={requirement.id}
                className={met ? 'text-success' : 'text-on-surface-variant'}
                aria-live="polite"
              >
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

        <div className="flex flex-col gap-xs">
          <label className="flex items-start gap-sm text-body-md text-on-surface">
            <input
              type="checkbox"
              className="mt-[3px] h-4 w-4 shrink-0 rounded border-outline-variant text-primary focus:ring-primary"
              {...register('termsAccepted')}
            />
            <span>
              I agree to the{' '}
              <Link to="/terms" className="font-semibold text-primary hover:underline">
                Terms
              </Link>{' '}
              and{' '}
              <Link to="/privacy" className="font-semibold text-primary hover:underline">
                Privacy Policy
              </Link>
              .
            </span>
          </label>
          {errors.termsAccepted?.message ? (
            <span className="text-label-sm text-error">{errors.termsAccepted.message}</span>
          ) : null}
        </div>

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
