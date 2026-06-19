import { zodResolver } from '@hookform/resolvers/zod';
import type { ParkioApiError } from '@parkio/api-client';
import {
  MODERATION_ACTIONS,
  MODERATION_STATUSES,
  type AppealStatus,
  type ModerationAppeal,
  type ModerationCase,
  type ModerationSeverity,
  type ModerationStatus,
} from '@parkio/types';
import {
  Button,
  Card,
  EmptyState,
  Icon,
  LoadingState,
  PageShell,
  SoftBadge,
  cn,
  type BadgeTone,
} from '@parkio/ui';
import {
  resolveAppealSchema,
  resolveCaseSchema,
  type ResolveAppealFormValues,
  type ResolveCaseFormValues,
} from '@parkio/validation';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { useForm } from 'react-hook-form';
import { Link } from 'react-router-dom';
import { moderationApi } from '@/api';
import { FriendlyApiErrorMessage } from '@/components/FriendlyApiErrorMessage';
import { formatInstant, formatRelativeAgo, humanizeEnum } from '@/lib/format';
import { showError, showSuccess } from '@/lib/toast';

const CASE_STATUS_TONE: Record<ModerationStatus, BadgeTone> = {
  OPEN: 'primary',
  IN_REVIEW: 'warning',
  RESOLVED: 'success',
  REJECTED: 'neutral',
};

const SEVERITY_TONE: Record<ModerationSeverity, BadgeTone> = {
  LOW: 'neutral',
  MEDIUM: 'primary',
  HIGH: 'warning',
  CRITICAL: 'danger',
};

const APPEAL_STATUS_TONE: Record<AppealStatus, BadgeTone> = {
  OPEN: 'primary',
  ACCEPTED: 'success',
  REJECTED: 'neutral',
};

const SELECT_CLASS =
  'rounded-lg border-0 bg-surface px-md py-sm text-body-md text-on-surface shadow-sm ring-1 ring-outline-variant/40 focus:outline-none focus:ring-2 focus:ring-primary';
const TEXTAREA_CLASS =
  'w-full rounded-lg border-0 bg-surface px-md py-sm text-body-md text-on-surface shadow-sm ring-1 ring-outline-variant/40 placeholder:text-outline focus:outline-none focus:ring-2 focus:ring-primary';

/**
 * Moderator/admin dashboard. Access is enforced by the gateway and the service
 * (403 FORBIDDEN); the app's RoleRoute mirrors that for UX only. Queue-first
 * layout: case list on the left, selected-case detail on the right, appeals
 * below — all backed by the existing query keys and mutations.
 */
export function ModerationPage() {
  const [selectedCaseId, setSelectedCaseId] = useState<string | null>(null);

  return (
    <PageShell title="Moderation">
      <div className="flex flex-col gap-lg">
        <div className="grid grid-cols-1 gap-lg lg:grid-cols-3 lg:items-start">
          <div className="lg:col-span-1">
            <CasesCard selectedCaseId={selectedCaseId} onSelect={setSelectedCaseId} />
          </div>
          <div className="lg:col-span-2">
            {selectedCaseId ? (
              <CaseDetailCard caseId={selectedCaseId} />
            ) : (
              <Card title="Case detail">
                <EmptyState
                  icon="gavel"
                  title="Select a case"
                  description="Choose a case from the queue to review its context and take action."
                />
              </Card>
            )}
          </div>
        </div>
        <AppealsCard />
      </div>
    </PageShell>
  );
}

function CasesCard({
  selectedCaseId,
  onSelect,
}: {
  selectedCaseId: string | null;
  onSelect: (caseId: string) => void;
}) {
  const [statusFilter, setStatusFilter] = useState<ModerationStatus | ''>('');

  const query = useQuery({
    queryKey: ['moderation', 'cases', statusFilter || 'all'],
    queryFn: () => moderationApi.getModerationCases(statusFilter || undefined),
  });

  return (
    <Card>
      <div className="mb-md flex flex-col gap-sm">
        <h2 className="m-0 text-title-lg text-on-surface">Cases</h2>
        <label className="flex flex-col gap-xs text-label-sm text-on-surface-variant">
          Filter by status
          <select
            value={statusFilter}
            onChange={(event) => setStatusFilter(event.target.value as ModerationStatus | '')}
            className={SELECT_CLASS}
          >
            <option value="">All (recent)</option>
            {MODERATION_STATUSES.map((status) => (
              <option key={status} value={status}>
                {humanizeEnum(status)}
              </option>
            ))}
          </select>
        </label>
      </div>

      {query.isPending ? (
        <LoadingState />
      ) : query.isError ? (
        <FriendlyApiErrorMessage error={query.error} mapper={mapModerationError} />
      ) : query.data.length === 0 ? (
        <EmptyState
          icon="inbox"
          title="No cases"
          description={
            statusFilter
              ? `No cases with status ${humanizeEnum(statusFilter)}.`
              : 'The moderation queue is clear.'
          }
        />
      ) : (
        <ul className="m-0 flex list-none flex-col gap-sm p-0">
          {query.data.map((moderationCase) => (
            <CaseListItem
              key={moderationCase.id}
              moderationCase={moderationCase}
              selected={moderationCase.id === selectedCaseId}
              onSelect={() => onSelect(moderationCase.id)}
            />
          ))}
        </ul>
      )}
    </Card>
  );
}

function CaseListItem({
  moderationCase,
  selected,
  onSelect,
}: {
  moderationCase: ModerationCase;
  selected: boolean;
  onSelect: () => void;
}) {
  return (
    <li>
      <button
        type="button"
        onClick={onSelect}
        aria-pressed={selected}
        className={cn(
          'flex w-full flex-col items-start gap-xs rounded-xl border p-md text-left transition-colors duration-std',
          'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary',
          selected
            ? 'border-l-4 border-primary bg-primary/5'
            : 'border-outline-variant/40 bg-surface-container-low hover:border-primary/40',
        )}
      >
        <div className="flex w-full flex-wrap items-center gap-xs">
          <span className="text-body-md font-semibold text-on-surface">
            {humanizeEnum(moderationCase.reason)}
          </span>
          <SoftBadge tone={SEVERITY_TONE[moderationCase.severity]}>
            {humanizeEnum(moderationCase.severity)}
          </SoftBadge>
          <SoftBadge tone={CASE_STATUS_TONE[moderationCase.status]}>
            {humanizeEnum(moderationCase.status)}
          </SoftBadge>
        </div>
        <span className="text-label-sm text-on-surface-variant">
          {humanizeEnum(moderationCase.targetType)} ·{' '}
          <span className="font-mono" title={moderationCase.targetId}>
            {moderationCase.targetId.slice(0, 8)}…
          </span>{' '}
          · {moderationCase.reportCount} report{moderationCase.reportCount === 1 ? '' : 's'} ·{' '}
          {moderationCase.assignedModeratorId ? 'assigned' : 'unassigned'}
        </span>
        <span className="text-label-sm text-on-surface-variant">
          Opened {formatRelativeAgo(moderationCase.openedAt)}
        </span>
      </button>
    </li>
  );
}

function CaseDetailCard({ caseId }: { caseId: string }) {
  const queryClient = useQueryClient();

  const query = useQuery({
    queryKey: ['moderation', 'case', caseId],
    queryFn: () => moderationApi.getModerationCase(caseId),
  });

  const invalidateCases = () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: ['moderation', 'cases'] }),
      queryClient.invalidateQueries({ queryKey: ['moderation', 'case', caseId] }),
    ]);

  const assignMutation = useMutation({
    mutationFn: () => moderationApi.assignModerationCase(caseId),
    onSuccess: () => {
      showSuccess('Case assigned to you.');
      void invalidateCases();
    },
    onError: () => showError('Could not assign case.'),
  });

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<ResolveCaseFormValues>({
    resolver: zodResolver(resolveCaseSchema),
    defaultValues: { note: '' },
  });

  const resolveMutation = useMutation({
    mutationFn: (values: ResolveCaseFormValues) =>
      moderationApi.resolveModerationCase(caseId, {
        action: values.action,
        note: values.note === '' ? null : values.note,
      }),
    onSuccess: async () => {
      reset();
      await invalidateCases();
      showSuccess('Case resolved.');
    },
    onError: () => showError('Could not resolve case.'),
  });

  if (query.isPending) {
    return (
      <Card title="Case detail">
        <LoadingState />
      </Card>
    );
  }
  if (query.isError) {
    return (
      <Card title="Case detail">
        <FriendlyApiErrorMessage error={query.error} mapper={mapModerationError} />
      </Card>
    );
  }

  const moderationCase = query.data;
  const terminal = moderationCase.status === 'RESOLVED' || moderationCase.status === 'REJECTED';
  const pending = assignMutation.isPending || resolveMutation.isPending;
  const onResolve = handleSubmit((values) => resolveMutation.mutate(values));

  return (
    <Card>
      <div className="flex flex-col gap-md">
        {/* Header */}
        <div className="flex flex-wrap items-start justify-between gap-sm">
          <div className="min-w-0">
            <h2 className="m-0 text-title-lg text-on-surface">
              {humanizeEnum(moderationCase.reason)}
            </h2>
            <p className="m-0 mt-xs font-mono text-label-sm text-on-surface-variant">
              {moderationCase.id}
            </p>
          </div>
          <div className="flex flex-wrap items-center gap-xs">
            <SoftBadge tone={SEVERITY_TONE[moderationCase.severity]} icon="priority_high">
              {humanizeEnum(moderationCase.severity)}
            </SoftBadge>
            <SoftBadge tone={CASE_STATUS_TONE[moderationCase.status]}>
              {humanizeEnum(moderationCase.status)}
            </SoftBadge>
          </div>
        </div>

        {/* Context — only fields the backend actually exposes (no media/evidence). */}
        <dl className="m-0 grid grid-cols-1 gap-sm sm:grid-cols-2">
          <Field label="Target type" value={humanizeEnum(moderationCase.targetType)} />
          <Field
            label="Target"
            value={
              moderationCase.targetType === 'PARKING_SPOT' ? (
                <Link
                  to={`/spots/${moderationCase.targetId}`}
                  className="font-mono text-primary hover:underline"
                >
                  {moderationCase.targetId}
                </Link>
              ) : (
                <span className="break-all font-mono">{moderationCase.targetId}</span>
              )
            }
          />
          <Field label="Reports" value={moderationCase.reportCount} />
          <Field
            label="Assigned moderator"
            value={
              moderationCase.assignedModeratorId ? (
                <span className="break-all font-mono">{moderationCase.assignedModeratorId}</span>
              ) : (
                'Unassigned'
              )
            }
          />
          <Field label="Opened" value={formatInstant(moderationCase.openedAt)} />
          <Field label="Updated" value={formatInstant(moderationCase.updatedAt)} />
        </dl>

        {terminal ? (
          <div className="rounded-xl bg-surface-container-low p-md">
            <p className="m-0 text-body-md text-on-surface">
              Resolution: <strong>{moderationCase.resolutionAction ?? '—'}</strong>
              {moderationCase.resolvedAt ? ` · ${formatInstant(moderationCase.resolvedAt)}` : ''}
            </p>
            {moderationCase.resolutionNote ? (
              <p className="m-0 mt-xs text-body-md text-on-surface-variant">
                {moderationCase.resolutionNote}
              </p>
            ) : null}
            <p className="m-0 mt-sm flex items-center gap-xs text-label-sm text-on-surface-variant">
              <Icon name="lock" className="text-[14px] leading-none" />
              This case is closed — it can no longer be assigned or resolved.
            </p>
          </div>
        ) : (
          <>
            <div className="flex flex-col items-start gap-sm">
              <Button variant="secondary" onClick={() => assignMutation.mutate()} disabled={pending}>
                <Icon name="assignment_ind" className="text-[16px] leading-none" />
                {assignMutation.isPending ? 'Assigning…' : 'Assign to me'}
              </Button>
              {assignMutation.isError ? (
                <FriendlyApiErrorMessage error={assignMutation.error} mapper={mapModerationError} />
              ) : null}
            </div>

            <form onSubmit={onResolve}>
              <fieldset disabled={pending} className="m-0 flex flex-col gap-sm border-0 p-0">
                <label className="flex flex-col gap-xs text-label-md font-semibold text-on-surface">
                  Resolve with action
                  <span className="text-label-sm font-normal text-on-surface-variant">
                    APPROVE dismisses the case; any other action upholds it.
                  </span>
                  <select defaultValue="" className={SELECT_CLASS} {...register('action')}>
                    <option value="">Select an action…</option>
                    {MODERATION_ACTIONS.map((action) => (
                      <option key={action} value={action}>
                        {humanizeEnum(action)}
                      </option>
                    ))}
                  </select>
                </label>
                {errors.action ? (
                  <p className="m-0 text-label-sm text-error">{errors.action.message}</p>
                ) : null}

                <label className="flex flex-col gap-xs text-label-md font-semibold text-on-surface">
                  Note (optional)
                  <textarea rows={3} className={TEXTAREA_CLASS} {...register('note')} />
                </label>
                {errors.note ? (
                  <p className="m-0 text-label-sm text-error">{errors.note.message}</p>
                ) : null}

                <Button type="submit" disabled={pending} className="self-start">
                  {resolveMutation.isPending ? 'Resolving…' : 'Resolve case'}
                </Button>
              </fieldset>
            </form>
            {resolveMutation.isError ? (
              <FriendlyApiErrorMessage error={resolveMutation.error} mapper={mapModerationError} />
            ) : null}
          </>
        )}
        {resolveMutation.isSuccess ? (
          <p className="m-0 flex items-center gap-xs text-label-sm text-secondary">
            <Icon name="check_circle" className="text-[14px] leading-none" />
            Case resolved.
          </p>
        ) : null}
      </div>
    </Card>
  );
}

function AppealsCard() {
  const query = useQuery({
    queryKey: ['moderation', 'appeals'],
    queryFn: moderationApi.getModerationAppeals,
  });

  return (
    <Card title="Appeals">
      {query.isPending ? (
        <LoadingState />
      ) : query.isError ? (
        <FriendlyApiErrorMessage error={query.error} mapper={mapModerationError} />
      ) : query.data.length === 0 ? (
        <EmptyState icon="balance" title="No appeals yet" />
      ) : (
        <ul className="m-0 flex list-none flex-col gap-sm p-0">
          {query.data.map((appeal) => (
            <AppealItem key={appeal.id} appeal={appeal} />
          ))}
        </ul>
      )}
      <p className="m-0 mt-md text-label-sm text-on-surface-variant">
        Recent appeals in every status — the backend has no status filter for appeals.
      </p>
    </Card>
  );
}

function AppealItem({ appeal }: { appeal: ModerationAppeal }) {
  return (
    <li className="rounded-xl border border-outline-variant/40 bg-surface-container-low p-md">
      <div className="flex flex-wrap items-center gap-sm">
        <SoftBadge tone={APPEAL_STATUS_TONE[appeal.status]}>{humanizeEnum(appeal.status)}</SoftBadge>
        <span className="text-label-sm text-on-surface-variant">
          {formatRelativeAgo(appeal.createdAt)}
        </span>
      </div>
      <p className="m-0 mt-sm text-label-sm text-on-surface-variant">
        Case: <span className="break-all font-mono">{appeal.caseId}</span> · Appellant:{' '}
        <span className="font-mono" title={appeal.appealUserId}>
          {appeal.appealUserId.slice(0, 8)}…
        </span>
      </p>
      {appeal.note ? <p className="m-0 mt-xs text-body-md text-on-surface">{appeal.note}</p> : null}
      {appeal.status === 'OPEN' ? (
        <ResolveAppealForm appealId={appeal.id} />
      ) : (
        <p className="m-0 mt-xs text-label-sm text-on-surface-variant">
          {appeal.resolvedAt ? `Resolved ${formatInstant(appeal.resolvedAt)}` : 'Resolved'}
          {appeal.resolutionNote ? ` — ${appeal.resolutionNote}` : ''}
        </p>
      )}
    </li>
  );
}

function ResolveAppealForm({ appealId }: { appealId: string }) {
  const queryClient = useQueryClient();

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<ResolveAppealFormValues>({
    resolver: zodResolver(resolveAppealSchema),
    defaultValues: { note: '' },
  });

  const resolveMutation = useMutation({
    mutationFn: (values: ResolveAppealFormValues) =>
      moderationApi.resolveModerationAppeal(appealId, {
        accepted: values.accepted,
        note: values.note === '' ? null : values.note,
      }),
    onSuccess: () => {
      showSuccess('Appeal resolved.');
      void queryClient.invalidateQueries({ queryKey: ['moderation', 'appeals'] });
    },
    onError: () => showError('Could not resolve appeal.'),
  });

  const onSubmit = handleSubmit((values) => resolveMutation.mutate(values));

  return (
    <form onSubmit={onSubmit} className="mt-sm">
      <fieldset disabled={resolveMutation.isPending} className="m-0 flex flex-col gap-sm border-0 p-0">
        <div className="flex gap-md text-body-md text-on-surface">
          <label className="flex items-center gap-xs">
            <input type="radio" value="true" className="text-primary focus:ring-primary" {...register('accepted')} />
            Accept
          </label>
          <label className="flex items-center gap-xs">
            <input type="radio" value="false" className="text-primary focus:ring-primary" {...register('accepted')} />
            Reject
          </label>
        </div>
        {errors.accepted ? (
          <p className="m-0 text-label-sm text-error">{errors.accepted.message}</p>
        ) : null}

        <label className="flex flex-col gap-xs text-label-sm text-on-surface-variant">
          Resolution note (optional)
          <textarea rows={2} className={TEXTAREA_CLASS} {...register('note')} />
        </label>
        {errors.note ? <p className="m-0 text-label-sm text-error">{errors.note.message}</p> : null}

        <Button type="submit" disabled={resolveMutation.isPending} className="self-start">
          {resolveMutation.isPending ? 'Resolving…' : 'Resolve appeal'}
        </Button>
      </fieldset>
      {resolveMutation.isError ? (
        <div className="mt-sm">
          <FriendlyApiErrorMessage error={resolveMutation.error} mapper={mapModerationError} />
        </div>
      ) : null}
    </form>
  );
}

function Field({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className="rounded-lg bg-surface-container-low p-sm">
      <dt className="m-0 text-label-sm text-on-surface-variant">{label}</dt>
      <dd className="m-0 mt-xs text-body-md text-on-surface">{value}</dd>
    </div>
  );
}

/** Friendly wording for expected moderation failures; null falls back to the raw ApiError. */
function mapModerationError(error: ParkioApiError): string | null {
  if (error.status === 403) {
    return 'Moderator or admin role required.';
  }
  if (error.status === 404) {
    return 'Not found — it may have been removed.';
  }
  if (error.status === 409) {
    return error.code === 'INVALID_APPEAL_STATE'
      ? 'This appeal was already resolved.'
      : 'This case is already resolved or dismissed.';
  }
  return null;
}
