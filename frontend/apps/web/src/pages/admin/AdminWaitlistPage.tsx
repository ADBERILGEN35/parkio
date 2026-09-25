import type { WaitlistSubscriptionStatus } from '@parkio/types';
import { Button, Card, EmptyState, Input, LoadingState, PageShell, SoftBadge } from '@parkio/ui';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router-dom';
import { useParkioSdk } from '@/app/AppRuntimeContext';
import { FriendlyApiErrorMessage } from '@/components/FriendlyApiErrorMessage';
import { adminKeys } from '@/data/keys';

const STATUSES: Array<WaitlistSubscriptionStatus | ''> = [
  '',
  'PENDING',
  'CONFIRMED',
  'WITHDRAWN',
];

function formatInstant(value: string | null | undefined, fallback: string): string {
  if (!value) return fallback;
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return fallback;
  return date.toLocaleString();
}

function downloadCsv(filename: string, csv: string) {
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  URL.revokeObjectURL(url);
}

/**
 * Operator visibility for registration notification-list subscriptions.
 * These are not application accounts — confirmations do not open product login.
 */
export function AdminWaitlistPage() {
  const { waitlistApi } = useParkioSdk();
  const { t } = useTranslation('admin');
  const [params, setParams] = useSearchParams();
  const [exportError, setExportError] = useState<unknown>(null);
  const [exporting, setExporting] = useState(false);

  const status = (params.get('status') ?? '') as WaitlistSubscriptionStatus | '';
  const createdFrom = params.get('createdFrom') ?? '';
  const createdTo = params.get('createdTo') ?? '';
  const page = Number(params.get('page') ?? '0') || 0;

  const summaryQuery = useQuery({
    queryKey: adminKeys.waitlistSummary(),
    queryFn: () => waitlistApi.adminSummary(),
  });

  const listQuery = useQuery({
    queryKey: adminKeys.waitlist(status, createdFrom, createdTo, page),
    queryFn: () =>
      waitlistApi.adminList({
        status: status || undefined,
        createdFrom: createdFrom || undefined,
        createdTo: createdTo || undefined,
        page,
        size: 20,
      }),
  });

  function update(next: Record<string, string>) {
    const merged = new URLSearchParams(params);
    for (const [k, v] of Object.entries(next)) {
      if (!v) merged.delete(k);
      else merged.set(k, v);
    }
    if (!('page' in next)) merged.set('page', '0');
    setParams(merged);
  }

  async function onExportConfirmed() {
    setExportError(null);
    setExporting(true);
    try {
      const csv = await waitlistApi.exportConfirmedCsv({
        createdFrom: createdFrom || undefined,
        createdTo: createdTo || undefined,
      });
      downloadCsv('parkio-waitlist-confirmed.csv', csv);
    } catch (error) {
      setExportError(error);
    } finally {
      setExporting(false);
    }
  }

  return (
    <PageShell title={t('waitlist.title')}>
      <p className="mb-lg mt-0 text-body-md text-on-surface-variant">{t('waitlist.subtitle')}</p>
      <p className="mb-lg mt-0 text-body-sm text-on-surface-variant">{t('waitlist.distinction')}</p>

      <Card title={t('waitlist.countsTitle')} className="mb-lg">
        <p className="mb-sm mt-0 text-body-sm text-on-surface-variant">{t('waitlist.countsNote')}</p>
        {summaryQuery.isPending ? (
          <LoadingState label={t('common:actions.loading')} />
        ) : summaryQuery.isError ? (
          <FriendlyApiErrorMessage error={summaryQuery.error} />
        ) : (
          <div className="flex flex-wrap gap-md">
            <SoftBadge>{t('waitlist.counts.pending', { count: summaryQuery.data.pending })}</SoftBadge>
            <SoftBadge>{t('waitlist.counts.confirmed', { count: summaryQuery.data.confirmed })}</SoftBadge>
            <SoftBadge>{t('waitlist.counts.withdrawn', { count: summaryQuery.data.withdrawn })}</SoftBadge>
            <SoftBadge>{t('waitlist.counts.total', { count: summaryQuery.data.total })}</SoftBadge>
          </div>
        )}
      </Card>

      <Card title={t('waitlist.filters')}>
        <div className="flex flex-col gap-sm md:flex-row md:items-end">
          <label>
            <span className="mb-xs block text-label-md font-semibold">{t('waitlist.statusLabel')}</span>
            <select
              className="h-11 rounded-md border border-outline-variant bg-surface px-sm text-body-md"
              value={status}
              onChange={(e) => update({ status: e.target.value })}
            >
              {STATUSES.map((s) => (
                <option key={s || 'all'} value={s}>
                  {s ? t(`waitlist.status.${s}`) : t('waitlist.status.all')}
                </option>
              ))}
            </select>
          </label>
          <label>
            <span className="mb-xs block text-label-md font-semibold">{t('waitlist.createdFrom')}</span>
            <Input
              type="datetime-local"
              value={createdFrom ? createdFrom.slice(0, 16) : ''}
              onChange={(e) =>
                update({
                  createdFrom: e.target.value ? new Date(e.target.value).toISOString() : '',
                })
              }
            />
          </label>
          <label>
            <span className="mb-xs block text-label-md font-semibold">{t('waitlist.createdTo')}</span>
            <Input
              type="datetime-local"
              value={createdTo ? createdTo.slice(0, 16) : ''}
              onChange={(e) =>
                update({
                  createdTo: e.target.value ? new Date(e.target.value).toISOString() : '',
                })
              }
            />
          </label>
          <Button type="button" variant="ghost" onClick={() => setParams(new URLSearchParams())}>
            {t('common.reset')}
          </Button>
          <Button type="button" onClick={() => void onExportConfirmed()} disabled={exporting}>
            {exporting ? t('waitlist.exporting') : t('waitlist.exportConfirmed')}
          </Button>
        </div>
        {exportError ? (
          <div className="mt-sm">
            <FriendlyApiErrorMessage error={exportError} />
          </div>
        ) : null}
        <p className="mb-0 mt-sm text-body-sm text-on-surface-variant">{t('waitlist.exportNote')}</p>
      </Card>

      <Card title={t('waitlist.subscriptions')} className="mt-lg">
        {listQuery.isPending ? (
          <LoadingState label={t('common:actions.loading')} />
        ) : listQuery.isError ? (
          <FriendlyApiErrorMessage error={listQuery.error} />
        ) : listQuery.data.content.length === 0 ? (
          <EmptyState title={t('waitlist.emptyTitle')} description={t('waitlist.emptyDescription')} />
        ) : (
          <>
            <div className="overflow-x-auto">
              <table className="w-full min-w-[720px] border-collapse text-left text-body-sm">
                <thead>
                  <tr className="border-b border-outline-variant/50 text-label-md uppercase tracking-wide text-on-surface-variant">
                    <th className="px-sm py-xs font-semibold">{t('waitlist.table.fullName')}</th>
                    <th className="px-sm py-xs font-semibold">{t('waitlist.table.email')}</th>
                    <th className="px-sm py-xs font-semibold">{t('waitlist.table.status')}</th>
                    <th className="px-sm py-xs font-semibold">{t('waitlist.table.locale')}</th>
                    <th className="px-sm py-xs font-semibold">{t('waitlist.table.source')}</th>
                    <th className="px-sm py-xs font-semibold">{t('waitlist.table.created')}</th>
                    <th className="px-sm py-xs font-semibold">{t('waitlist.table.confirmed')}</th>
                    <th className="px-sm py-xs font-semibold">{t('waitlist.table.withdrawn')}</th>
                  </tr>
                </thead>
                <tbody>
                  {listQuery.data.content.map((row) => (
                    <tr key={row.id} className="border-b border-outline-variant/30">
                      <td className="px-sm py-sm font-medium">
                        {row.fullName?.trim()
                          ? row.fullName
                          : t('waitlist.table.nameNotProvided')}
                      </td>
                      <td className="px-sm py-sm font-medium">{row.email}</td>
                      <td className="px-sm py-sm">
                        <SoftBadge>{t(`waitlist.status.${row.status}`)}</SoftBadge>
                      </td>
                      <td className="px-sm py-sm">{row.locale || t('common.na')}</td>
                      <td className="px-sm py-sm">
                        {row.source ? row.source : t('waitlist.sourceUnknown')}
                      </td>
                      <td className="px-sm py-sm">{formatInstant(row.createdAt, t('common.na'))}</td>
                      <td className="px-sm py-sm">{formatInstant(row.confirmedAt, t('common.na'))}</td>
                      <td className="px-sm py-sm">{formatInstant(row.withdrawnAt, t('common.na'))}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="mt-md flex items-center justify-between gap-sm">
              <Button
                type="button"
                variant="ghost"
                disabled={page <= 0}
                onClick={() => update({ page: String(page - 1) })}
              >
                {t('common.previous')}
              </Button>
              <span className="text-body-sm text-on-surface-variant">
                {t('waitlist.pageLabel', {
                  page: page + 1,
                  pages: Math.max(listQuery.data.totalPages, 1),
                  total: listQuery.data.totalElements,
                })}
              </span>
              <Button
                type="button"
                variant="ghost"
                disabled={page + 1 >= listQuery.data.totalPages}
                onClick={() => update({ page: String(page + 1) })}
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
