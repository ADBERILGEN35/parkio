import { zodResolver } from '@hookform/resolvers/zod';
import { passwordRequirementState, type RegisterProfileFormValues } from '@parkio/validation';
import { Button, ErrorMessage, Icon, Input } from '@parkio/ui';
import { useMemo, useState, useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { describeAuthError } from '@/api/error-messages';
import { useParkioSdk } from '@/app/AppRuntimeContext';
import { AuthSplitLayout } from '@/pages/auth/AuthSplitLayout';
import { setPendingProfile } from '@/auth/pendingProfile';
import {
  createRegisterProfileSchema,
  getPasswordRequirements,
} from '@/lib/validation/localized-schemas';
import { showError, showSuccess } from '@/lib/toast';
import { useRegistrationMode } from '@/auth/useRegistrationMode';

export function RegisterPage() {
  const { authApi } = useParkioSdk();
  const { t, i18n } = useTranslation(['auth', 'common', 'validation', 'errors']);
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const [inviteToken, setInviteToken] = useState<string | null>(null);
  const [apiError, setApiError] = useState<string | null>(null);
  const [traceId, setTraceId] = useState<string | undefined>();
  const registrationMode = useRegistrationMode();

  const schema = useMemo(() => createRegisterProfileSchema(t), [t]);
  const requirements = useMemo(() => getPasswordRequirements(t), [t]);

  const {
    register,
    handleSubmit,
    setError,
    watch,
    formState: { errors, isSubmitting },
  } = useForm<RegisterProfileFormValues>({
    resolver: zodResolver(schema),
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

  useEffect(() => {
    const token = searchParams.get('invite')?.trim();
    if (!token) {
      return;
    }
    setInviteToken(token);
    const next = new URLSearchParams(searchParams);
    next.delete('invite');
    setSearchParams(next, { replace: true });
  }, [searchParams, setSearchParams]);

  const onSubmit = handleSubmit(async (values) => {
    setApiError(null);
    setTraceId(undefined);
    try {
      await authApi.register({
        email: values.email,
        password: values.password,
        locale: i18n.language === 'en' ? 'en' : 'tr',
        inviteToken: inviteToken ?? undefined,
      });
      setPendingProfile({
        displayName: values.displayName.trim(),
        phoneNumber: values.phoneNumber?.trim() || undefined,
      });
      showSuccess(t('auth:register.success'));
      navigate(`/check-email?email=${encodeURIComponent(values.email.trim())}`);
    } catch (error) {
      const friendly = describeAuthError(error, t('errors:auth.registrationFailed'), t);
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

  if (registrationMode === 'CLOSED') {
    return (
      <AuthSplitLayout title={t('auth:register.closedTitle')} subtitle={t('auth:register.closedMessage')}>
        <div className="flex flex-col gap-sm">
          <Link
            to="/explore"
            className="inline-flex min-h-11 items-center justify-center rounded-full bg-primary px-lg py-sm text-label-md font-semibold text-on-primary"
          >
            {t('auth:register.exploreLive')}
          </Link>
          <Link
            to="/login"
            className="inline-flex min-h-11 items-center justify-center rounded-full border border-outline px-lg py-sm text-label-md font-semibold text-on-surface"
          >
            {t('auth:register.signInLink')}
          </Link>
        </div>
      </AuthSplitLayout>
    );
  }

  return (
    <AuthSplitLayout title={t('auth:register.title')} subtitle={t('auth:register.subtitle')}>
      <form onSubmit={onSubmit} className="flex flex-col gap-md">
        <Input
          label={t('auth:register.displayName')}
          autoComplete="name"
          error={errors.displayName?.message}
          {...register('displayName')}
        />
        <Input
          label={t('auth:register.email')}
          type="email"
          autoComplete="email"
          error={errors.email?.message}
          {...register('email')}
        />
        <div className="flex flex-col gap-xs">
          <Input
            label={t('auth:register.phone')}
            type="tel"
            autoComplete="tel"
            error={errors.phoneNumber?.message}
            {...register('phoneNumber')}
          />
          <p className="m-0 text-label-sm text-on-surface-variant">{t('auth:register.phoneHint')}</p>
        </div>
        <Input
          label={t('auth:register.password')}
          type="password"
          autoComplete="new-password"
          error={errors.password?.message}
          {...register('password')}
        />
        <ul className="m-0 grid list-none gap-1 p-0 text-label-sm text-on-surface-variant">
          {requirements.map((requirement) => {
            const met = passwordState[requirement.id];
            return (
              <li
                key={requirement.id}
                className={met ? 'text-success' : 'text-on-surface-variant'}
                aria-live="polite"
              >
                {met ? t('auth:register.requirementMet') : t('auth:register.requirementNeeded')}{' '}
                {requirement.label}
              </li>
            );
          })}
        </ul>
        <Input
          label={t('auth:register.confirmPassword')}
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
              {t('auth:register.termsPrefix')}{' '}
              <a href="https://parkio.dev/terms/" className="font-semibold text-primary hover:underline">
                {t('auth:register.terms')}
              </a>{' '}
              {t('auth:register.termsAnd')}{' '}
              <a href="https://parkio.dev/privacy/" className="font-semibold text-primary hover:underline">
                {t('auth:register.privacy')}
              </a>
              .
            </span>
          </label>
          {errors.termsAccepted?.message ? (
            <span className="text-label-sm text-error">{errors.termsAccepted.message}</span>
          ) : null}
        </div>

        {apiError ? <ErrorMessage message={apiError} traceId={traceId} /> : null}

        <Button type="submit" disabled={isSubmitting} className="w-full">
          {isSubmitting ? t('auth:register.submitting') : t('auth:register.submit')}
          {isSubmitting ? null : <Icon name="arrow_forward" className="text-[18px] leading-none" />}
        </Button>
      </form>

      <p className="m-0 mt-md text-center text-body-md text-on-surface-variant">
        {t('auth:register.hasAccount')}{' '}
        <Link to="/login" className="font-semibold text-primary hover:underline">
          {t('auth:register.signInLink')}
        </Link>
      </p>
    </AuthSplitLayout>
  );
}
