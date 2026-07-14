import { zodResolver } from '@hookform/resolvers/zod';
import type { ParkioApiError } from '@parkio/api-client';
import type { AppealStatus, ModerationAppeal, ModerationReport } from '@parkio/types';
import {
  Button,
  Card,
  EmptyState,
  Icon,
  Input,
  LoadingState,
  PageShell,
  SoftBadge,
  type BadgeTone,
} from '@parkio/ui';
import { createAppealSchema, type CreateAppealFormValues } from '@parkio/validation';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { moderationApi } from '@/api';
import { FriendlyApiErrorMessage } from '@/components/FriendlyApiErrorMessage';
import { ProductCard } from '@/components/product/ProductCard';
import { SettingsSectionCard } from '@/components/product/SettingsSectionCard';
import { enumLabel, formatRelativeAgo } from '@/lib/format';
import { showError, showSuccess } from '@/lib/toast';

const TEXTAREA_CLASS =
  'w-full rounded-lg border-0 bg-surface px-md py-sm text-body-md text-on-surface shadow-sm ring-1 ring-outline-variant/40 placeholder:text-outline focus:outline-none focus:ring-2 focus:ring-primary';

const APPEAL_STATUS_TONE: Record<AppealStatus, BadgeTone> = {
  OPEN: 'primary',
  ACCEPTED: 'success',
  REJECTED: 'neutral',
};

export function ReportsPage() {
  const { t } = useTranslation('parking');
  return (
    <PageShell title={t('reports.title')}>
      <div className="grid grid-cols-1 gap-lg lg:grid-cols-3 lg:items-start">
        <div className="lg:col-span-2">
          <MyReportsCard />
        </div>
        <div className="lg:col-span-1">
          <AppealCard />
        </div>
      </div>
    </PageShell>
  );
}

function MyReportsCard() {
  const { t } = useTranslation('parking');
  const query = useQuery({ queryKey: ['reports'], queryFn: moderationApi.getMyReports });

  return (
    <Card title={t('reports.cardTitle')}>
      {query.isPending ? (
        <LoadingState />
      ) : query.isError ? (
        <FriendlyApiErrorMessage error={query.error} />
      ) : query.data.length === 0 ? (
        <EmptyState
          icon="flag"
          title={t('reports.emptyTitle')}
          description={t('reports.emptyDescription')}
        />
      ) : (
        <ul className="m-0 flex list-none flex-col gap-sm p-0">
          {query.data.map((report) => (
            <ReportItem key={report.id} report={report} />
          ))}
        </ul>
      )}
      <p className="m-0 mt-md flex items-start gap-xs text-label-sm text-on-surface-variant">
        <Icon name="info" className="text-[14px] leading-none" />
        {t('reports.footnote')}
      </p>
    </Card>
  );
}

function ReportItem({ report }: { report: ModerationReport }) {
  const { t } = useTranslation(['parking', 'common']);
  return (
    <ProductCard as="li" className="rounded-xl">
      <div className="flex flex-wrap items-center gap-xs">
        <span className="text-body-md font-semibold text-on-surface">
          {enumLabel(report.reason, t, ['reportReason', 'enums'])}
        </span>
        <SoftBadge tone="neutral">
          {enumLabel(report.targetType, t, ['reportTarget', 'enums'])}
        </SoftBadge>
      </div>

      <p className="m-0 mt-sm text-label-sm text-on-surface-variant">
        {t('reports.target')}{' '}
        {report.targetType === 'PARKING_SPOT' ? (
          <Link to={`/spots/${report.targetId}`} className="font-mono text-primary hover:underline">
            {report.targetId}
          </Link>
        ) : (
          <span className="break-all font-mono">{report.targetId}</span>
        )}
      </p>

      {report.description ? (
        <p className="m-0 mt-xs text-body-md text-on-surface">{report.description}</p>
      ) : null}

      <div className="mt-sm flex flex-wrap items-center gap-xs">
        {report.caseId ? (
          <SoftBadge tone="primary" icon="gavel">
            {t('reports.caseOpened')}
          </SoftBadge>
        ) : (
          <SoftBadge tone="neutral" icon="check_circle">
            {t('reports.recorded')}
          </SoftBadge>
        )}
        <span className="text-label-sm text-on-surface-variant">
          {formatRelativeAgo(report.createdAt)}
        </span>
      </div>
      {report.caseId ? (
        <p className="m-0 mt-xs text-label-sm text-on-surface-variant">
          {t('reports.caseLabel')} <span className="break-all font-mono">{report.caseId}</span>
        </p>
      ) : null}
    </ProductCard>
  );
}

/**
 * Appeals apply to a RESOLVED moderation case that targets *your own account* —
 * not to the cases opened by your reports (those target the reported spot/user).
 * The backend has no user-facing endpoint to list cases against you, so the case
 * id must be entered manually (e.g. from a warning notification).
 */
function AppealCard() {
  const { t } = useTranslation(['parking', 'common']);
  const [createdAppeal, setCreatedAppeal] = useState<ModerationAppeal | null>(null);

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<CreateAppealFormValues>({
    resolver: zodResolver(createAppealSchema),
    defaultValues: { caseId: '', note: '' },
  });

  const appealMutation = useMutation({
    mutationFn: (values: CreateAppealFormValues) =>
      moderationApi.createAppeal({
        caseId: values.caseId,
        note: values.note === '' ? null : values.note,
      }),
    onSuccess: (appeal) => {
      setCreatedAppeal(appeal);
      reset();
      showSuccess(t('reports.appealSuccess'));
    },
    onError: () => showError(t('reports.appealError')),
  });

  const onSubmit = handleSubmit((values) => appealMutation.mutate(values));

  return (
    <SettingsSectionCard
      title={t('reports.appealTitle')}
      icon="balance"
      description={t('reports.appealDescription')}
    >
      <p className="m-0 mb-sm flex items-start gap-xs text-label-sm text-on-surface-variant">
        <Icon name="info" className="text-[14px] leading-none" />
        {t('reports.appealHelp')}
      </p>
      <form onSubmit={onSubmit}>
        <fieldset disabled={appealMutation.isPending} className="m-0 flex flex-col gap-sm border-0 p-0">
          <Input
            label={t('reports.caseId')}
            placeholder="00000000-0000-0000-0000-000000000000"
            className="font-mono"
            error={errors.caseId?.message}
            {...register('caseId')}
          />

          <label className="flex flex-col gap-xs text-label-sm text-on-surface-variant">
            {t('reports.noteLabel')}
            <textarea rows={3} className={TEXTAREA_CLASS} {...register('note')} />
          </label>
          {errors.note ? (
            <p className="m-0 text-label-sm text-error">{errors.note.message}</p>
          ) : null}

          <Button type="submit" disabled={appealMutation.isPending} className="self-start">
            {appealMutation.isPending ? t('reports.submitting') : t('reports.submitAppeal')}
          </Button>
        </fieldset>
      </form>
      {appealMutation.isError ? (
        <div className="mt-sm">
          <FriendlyApiErrorMessage
            error={appealMutation.error}
            mapper={(error) => mapAppealError(error, t)}
          />
        </div>
      ) : null}
      {createdAppeal ? (
        <div className="mt-sm flex items-center gap-xs">
          <Icon name="check_circle" className="text-[16px] leading-none text-secondary" />
          <span className="text-label-sm text-on-surface-variant">
            {t('reports.appealSubmittedPrefix')}
          </span>
          <SoftBadge tone={APPEAL_STATUS_TONE[createdAppeal.status]}>
            {enumLabel(createdAppeal.status, t, ['appealStatus', 'enums'])}
          </SoftBadge>
        </div>
      ) : null}
    </SettingsSectionCard>
  );
}

/** Friendly wording for expected appeal failures; null falls back to the raw ApiError. */
function mapAppealError(
  error: ParkioApiError,
  t: (key: string) => string,
): string | null {
  if (error.status === 404) {
    return t('reports.caseNotFound');
  }
  if (error.status === 409) {
    return error.code === 'DUPLICATE_APPEAL'
      ? t('reports.duplicateAppeal')
      : t('reports.caseNotResolved');
  }
  return null;
}
