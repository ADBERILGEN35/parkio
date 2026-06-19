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
import { Link } from 'react-router-dom';
import { moderationApi } from '@/api';
import { FriendlyApiErrorMessage } from '@/components/FriendlyApiErrorMessage';
import { formatRelativeAgo, humanizeEnum } from '@/lib/format';
import { showError, showSuccess } from '@/lib/toast';

const TEXTAREA_CLASS =
  'w-full rounded-lg border-0 bg-surface px-md py-sm text-body-md text-on-surface shadow-sm ring-1 ring-outline-variant/40 placeholder:text-outline focus:outline-none focus:ring-2 focus:ring-primary';

const APPEAL_STATUS_TONE: Record<AppealStatus, BadgeTone> = {
  OPEN: 'primary',
  ACCEPTED: 'success',
  REJECTED: 'neutral',
};

export function ReportsPage() {
  return (
    <PageShell title="My reports">
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
  const query = useQuery({ queryKey: ['reports'], queryFn: moderationApi.getMyReports });

  return (
    <Card title="Reports you submitted">
      {query.isPending ? (
        <LoadingState />
      ) : query.isError ? (
        <FriendlyApiErrorMessage error={query.error} />
      ) : query.data.length === 0 ? (
        <EmptyState
          icon="flag"
          title="No reports yet"
          description="You haven't reported anything. You can report a spot from its detail page."
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
        Reports don't carry a status of their own — when a report opens a moderation case, its
        case id is shown here and moderators take it from there.
      </p>
    </Card>
  );
}

function ReportItem({ report }: { report: ModerationReport }) {
  return (
    <li className="rounded-xl border border-outline-variant/40 bg-surface-container-low p-md">
      <div className="flex flex-wrap items-center gap-xs">
        <span className="text-body-md font-semibold text-on-surface">
          {humanizeEnum(report.reason)}
        </span>
        <SoftBadge tone="neutral">{humanizeEnum(report.targetType)}</SoftBadge>
      </div>

      <p className="m-0 mt-sm text-label-sm text-on-surface-variant">
        Target:{' '}
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
            Case opened
          </SoftBadge>
        ) : (
          <SoftBadge tone="neutral" icon="check_circle">
            Recorded
          </SoftBadge>
        )}
        <span className="text-label-sm text-on-surface-variant">
          {formatRelativeAgo(report.createdAt)}
        </span>
      </div>
      {report.caseId ? (
        <p className="m-0 mt-xs text-label-sm text-on-surface-variant">
          Case: <span className="break-all font-mono">{report.caseId}</span>
        </p>
      ) : null}
    </li>
  );
}

/**
 * Appeals apply to a RESOLVED moderation case that targets *your own account* —
 * not to the cases opened by your reports (those target the reported spot/user).
 * The backend has no user-facing endpoint to list cases against you, so the case
 * id must be entered manually (e.g. from a warning notification).
 */
function AppealCard() {
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
      showSuccess('Appeal submitted.');
    },
    onError: () => showError('Could not submit appeal.'),
  });

  const onSubmit = handleSubmit((values) => appealMutation.mutate(values));

  return (
    <Card title="Appeal a moderation decision">
      <p className="m-0 mb-sm flex items-start gap-xs text-label-sm text-on-surface-variant">
        <Icon name="info" className="text-[14px] leading-none" />
        If a case against your account was resolved and you disagree, you can appeal it once.
        Enter the case id from the warning you received — there's no way to list cases against
        your account here yet.
      </p>
      <form onSubmit={onSubmit}>
        <fieldset disabled={appealMutation.isPending} className="m-0 flex flex-col gap-sm border-0 p-0">
          <Input
            label="Case id"
            placeholder="00000000-0000-0000-0000-000000000000"
            className="font-mono"
            error={errors.caseId?.message}
            {...register('caseId')}
          />

          <label className="flex flex-col gap-xs text-label-sm text-on-surface-variant">
            Why should this decision be reconsidered? (optional)
            <textarea rows={3} className={TEXTAREA_CLASS} {...register('note')} />
          </label>
          {errors.note ? (
            <p className="m-0 text-label-sm text-error">{errors.note.message}</p>
          ) : null}

          <Button type="submit" disabled={appealMutation.isPending} className="self-start">
            {appealMutation.isPending ? 'Submitting…' : 'Submit appeal'}
          </Button>
        </fieldset>
      </form>
      {appealMutation.isError ? (
        <div className="mt-sm">
          <FriendlyApiErrorMessage error={appealMutation.error} mapper={mapAppealError} />
        </div>
      ) : null}
      {createdAppeal ? (
        <div className="mt-sm flex items-center gap-xs">
          <Icon name="check_circle" className="text-[16px] leading-none text-secondary" />
          <span className="text-label-sm text-on-surface-variant">Appeal submitted —</span>
          <SoftBadge tone={APPEAL_STATUS_TONE[createdAppeal.status]}>
            {humanizeEnum(createdAppeal.status)}
          </SoftBadge>
        </div>
      ) : null}
    </Card>
  );
}

/** Friendly wording for expected appeal failures; null falls back to the raw ApiError. */
function mapAppealError(error: ParkioApiError): string | null {
  if (error.status === 404) {
    return 'Case not found — appeals are only possible for cases opened against your own account.';
  }
  if (error.status === 409) {
    return error.code === 'DUPLICATE_APPEAL'
      ? 'You have already appealed this case.'
      : 'This case has not been resolved yet — only a resolved case can be appealed.';
  }
  return null;
}
