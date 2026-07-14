import { type ParkioApiError, isParkioApiError } from '@parkio/api-client';
import { ErrorMessage } from '@parkio/ui';
import { useTranslation } from 'react-i18next';
import { describeLocalizedApiError } from '@/i18n/map-api-error';

export type ApiErrorMapper = (error: ParkioApiError) => string | null;

export interface ApiErrorAlertProps {
  error: unknown;
  mapper?: ApiErrorMapper;
  fallback?: string;
}

export function describeApiError(
  error: unknown,
  mapper?: ApiErrorMapper,
  fallback = 'Something went wrong. Please try again.',
) {
  if (isParkioApiError(error)) {
    return {
      message: mapper?.(error) ?? fallback,
      code: error.code,
      traceId: error.traceId || undefined,
    };
  }
  return { message: fallback };
}

export function ApiErrorAlert({ error, mapper, fallback }: ApiErrorAlertProps) {
  const { t } = useTranslation(['errors', 'common']);
  const resolvedFallback = fallback ?? t('errors:common.generic');
  if (isParkioApiError(error)) {
    const mapped = mapper?.(error);
    const described = mapped
      ? { message: mapped, code: error.code, traceId: error.traceId || undefined }
      : describeLocalizedApiError(error, t, 'errors:common.generic');
    return <ErrorMessage {...described} />;
  }
  return <ErrorMessage message={resolvedFallback} />;
}
