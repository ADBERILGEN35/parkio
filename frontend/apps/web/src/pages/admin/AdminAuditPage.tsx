import type { AdminAuditResult } from '@parkio/types';
import { Button, Card, EmptyState, Input, LoadingState, PageShell, SoftBadge } from '@parkio/ui';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router-dom';
import { useParkioSdk } from '@/app/AppRuntimeContext';
import { FriendlyApiErrorMessage } from '@/components/FriendlyApiErrorMessage';

export function AdminAuditPage() {
  const { adminApi } = useParkioSdk();
  const { t } = useTranslation('admin');
  const [params, setParams] = useSearchParams();
  const page = Number(params.get('page') ?? '0') || 0;
  const correlationId = params.get('correlationId') ?? '';
  const result = (params.get('result') ?? '') as AdminAuditResult | '';

  const query = useQuery({
    queryKey: ['admin', 'audit', page, result],
    queryFn: () =>
      adminApi.listAuditEvents({
        page,
        size: 25,
        // Protocol enum SUCCESS/FAILURE stays as API filter value.
        result: result || undefined,
        sort: 'occurredAt,desc',
      }),
  });

  return (
    <PageShell title={t('audit.title')}>
      <p className="mb-lg mt-0 text-body-md text-on-surface-variant">{t('audit.subtitle')}</p>
      <Card title={t('audit.filters')}>
        <div className="flex flex-col gap-sm md:flex-row md:items-end">
          <label>
            <span className="mb-xs block text-label-md font-semibold">{t('audit.resultLabel')}</span>
            <select
              className="h-11 rounded-md border border-outline-variant bg-surface px-sm"
              value={result}
              onChange={(e) => {
                const next = new URLSearchParams(params);
                if (e.target.value) next.set('result', e.target.value);
                else next.delete('result');
                next.set('page', '0');
                setParams(next);
              }}
            >
              <option value="">{t('audit.resultAll')}</option>
              <option value="SUCCESS">{t('audit.resultSuccess')}</option>
              <option value="FAILURE">{t('audit.resultFailure')}</option>
            </select>
          </label>
          <label className="min-w-0 flex-1">
            <span className="mb-xs block text-label-md font-semibold">{t('audit.correlationLabel')}</span>
            <Input
              value={correlationId}
              onChange={(e) => {
                const next = new URLSearchParams(params);
                if (e.target.value) next.set('correlationId', e.target.value);
                else next.delete('correlationId');
                setParams(next);
              }}
              placeholder={t('audit.correlationPlaceholder')}
            />
          </label>
        </div>
      </Card>

      <Card title={t('audit.events')} className="mt-lg">
        {query.isPending ? (
          <LoadingState label={t('common:actions.loading')} />
        ) : query.isError ? (
          <FriendlyApiErrorMessage error={query.error} />
        ) : query.data.content.length === 0 ? (
          <EmptyState title={t('audit.emptyTitle')} description={t('audit.emptyDescription')} />
        ) : (
          <>
            <ul className="m-0 list-none space-y-sm p-0">
              {query.data.content
                .filter((e) => !correlationId || e.correlationId?.includes(correlationId))
                .map((event) => (
                  <li
                    key={event.id}
                    className="rounded-md border border-outline-variant/40 px-sm py-sm text-body-sm"
                  >
                    <div className="flex flex-wrap items-center gap-sm">
                      <span className="font-semibold">{event.actionType}</span>
                      <SoftBadge tone={event.result === 'SUCCESS' ? 'success' : 'danger'}>
                        {event.result === 'SUCCESS' ? t('audit.resultSuccess') : t('audit.resultFailure')}
                      </SoftBadge>
                    </div>
                    <div className="mt-xs text-on-surface-variant">
                      {new Date(event.occurredAt).toLocaleString()} ·{' '}
                      {t('audit.eventActor', { actorId: event.actorUserId, roles: event.actorRoles })}
                    </div>
                    <div className="text-on-surface-variant">
                      {t('audit.eventTarget', {
                        type: event.targetResourceType,
                        id: event.targetResourceId ? ` ${event.targetResourceId}` : '',
                      })}
                      {event.reason ? ` · ${event.reason}` : ''}
                    </div>
                  </li>
                ))}
            </ul>
            <div className="mt-md flex justify-between">
              <Button
                type="button"
                variant="ghost"
                disabled={page <= 0}
                onClick={() => {
                  const next = new URLSearchParams(params);
                  next.set('page', String(page - 1));
                  setParams(next);
                }}
              >
                {t('common.previous')}
              </Button>
              <Button
                type="button"
                variant="ghost"
                disabled={page + 1 >= query.data.totalPages}
                onClick={() => {
                  const next = new URLSearchParams(params);
                  next.set('page', String(page + 1));
                  setParams(next);
                }}
              >
                {t('common.next')}
              </Button>
            </div>
          </>
        )}
      </Card>
    </PageShell>
  );
}
