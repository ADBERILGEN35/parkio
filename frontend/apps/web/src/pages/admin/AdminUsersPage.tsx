import type { AuthUserStatus } from '@parkio/types';
import { Button, Card, EmptyState, Input, LoadingState, PageShell, SoftBadge } from '@parkio/ui';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Link, useSearchParams } from 'react-router-dom';
import { useParkioSdk } from '@/app/AppRuntimeContext';
import { FriendlyApiErrorMessage } from '@/components/FriendlyApiErrorMessage';

const STATUSES: Array<AuthUserStatus | ''> = [
  '',
  'ACTIVE',
  'PENDING_VERIFICATION',
  'SUSPENDED',
  'BANNED',
];

function statusLabel(t: (key: string) => string, status: AuthUserStatus | ''): string {
  if (!status) return t('status.all');
  return t(`status.${status}`);
}

function roleLabel(t: (key: string) => string, role: string): string {
  const key = `roles.${role}`;
  const translated = t(key);
  return translated === key ? role : translated;
}

export function AdminUsersPage() {
  const { adminApi } = useParkioSdk();
  const { t } = useTranslation('admin');
  const [params, setParams] = useSearchParams();
  const q = params.get('q') ?? '';
  const status = (params.get('status') ?? '') as AuthUserStatus | '';
  const page = Number(params.get('page') ?? '0') || 0;

  const query = useQuery({
    queryKey: ['admin', 'users', q, status, page],
    queryFn: () =>
      adminApi.listUsers({
        q: q || undefined,
        status: status || undefined,
        page,
        size: 20,
        sort: 'createdAt,desc',
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

  return (
    <PageShell title={t('users.title')}>
      <p className="mb-lg mt-0 text-body-md text-on-surface-variant">{t('users.subtitle')}</p>
      <Card title={t('users.filters')}>
        <div className="flex flex-col gap-sm md:flex-row md:items-end">
          <label className="min-w-0 flex-1">
            <span className="mb-xs block text-label-md font-semibold">{t('users.searchLabel')}</span>
            <Input
              value={q}
              onChange={(e) => update({ q: e.target.value })}
              placeholder={t('users.searchPlaceholder')}
            />
          </label>
          <label>
            <span className="mb-xs block text-label-md font-semibold">{t('users.statusLabel')}</span>
            <select
              className="h-11 rounded-md border border-outline-variant bg-surface px-sm text-body-md"
              value={status}
              onChange={(e) => update({ status: e.target.value })}
            >
              {STATUSES.map((s) => (
                <option key={s || 'all'} value={s}>
                  {statusLabel(t, s)}
                </option>
              ))}
            </select>
          </label>
          <Button type="button" variant="ghost" onClick={() => setParams(new URLSearchParams())}>
            {t('common.reset')}
          </Button>
        </div>
      </Card>

      <Card title={t('users.accounts')} className="mt-lg">
        {query.isPending ? (
          <LoadingState label={t('common:actions.loading')} />
        ) : query.isError ? (
          <FriendlyApiErrorMessage error={query.error} />
        ) : query.data.content.length === 0 ? (
          <EmptyState title={t('users.emptyTitle')} description={t('users.emptyDescription')} />
        ) : (
          <>
            <div className="overflow-x-auto">
              <table className="w-full min-w-[640px] border-collapse text-left text-body-sm">
                <thead>
                  <tr className="border-b border-outline-variant/50 text-label-md uppercase tracking-wide text-on-surface-variant">
                    <th className="px-sm py-xs font-semibold">{t('users.table.email')}</th>
                    <th className="px-sm py-xs font-semibold">{t('users.table.status')}</th>
                    <th className="px-sm py-xs font-semibold">{t('users.table.verified')}</th>
                    <th className="px-sm py-xs font-semibold">{t('users.table.roles')}</th>
                    <th className="px-sm py-xs font-semibold">{t('users.table.sessions')}</th>
                    <th className="px-sm py-xs font-semibold">{t('users.table.created')}</th>
                  </tr>
                </thead>
                <tbody>
                  {query.data.content.map((user) => (
                    <tr key={user.id} className="border-b border-outline-variant/30">
                      <td className="px-sm py-sm">
                        <Link className="font-medium text-primary no-underline hover:underline" to={`/admin/users/${user.id}`}>
                          {user.email}
                        </Link>
                        <div className="font-mono text-body-sm text-on-surface-variant">{user.id}</div>
                      </td>
                      <td className="px-sm py-sm">
                        <SoftBadge tone={user.status === 'ACTIVE' ? 'success' : 'warning'}>
                          {statusLabel(t, user.status)}
                        </SoftBadge>
                      </td>
                      <td className="px-sm py-sm">{user.emailVerified ? t('common.yes') : t('common.no')}</td>
                      <td className="px-sm py-sm">{user.roles.map((role) => roleLabel(t, role)).join(', ')}</td>
                      <td className="px-sm py-sm">{user.activeSessionCount}</td>
                      <td className="px-sm py-sm">{new Date(user.createdAt).toLocaleString()}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="mt-md flex items-center justify-between gap-sm">
              <p className="m-0 text-body-sm text-on-surface-variant">
                {t('users.pagination.summary', {
                  page: query.data.page + 1,
                  totalPages: Math.max(query.data.totalPages, 1),
                  total: query.data.totalElements,
                })}
              </p>
              <div className="flex gap-xs">
                <Button
                  type="button"
                  variant="ghost"
                  disabled={page <= 0}
                  onClick={() => update({ page: String(page - 1) })}
                >
                  {t('common.previous')}
                </Button>
                <Button
                  type="button"
                  variant="ghost"
                  disabled={page + 1 >= query.data.totalPages}
                  onClick={() => update({ page: String(page + 1) })}
                >
                  {t('common.next')}
                </Button>
              </div>
            </div>
          </>
        )}
      </Card>
    </PageShell>
  );
}
