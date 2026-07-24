import type { AdminRoleName } from '@parkio/types';
import { hasSuperAdminRole } from '@parkio/types';
import { Button, Card, EmptyState, LoadingState, PageShell, SoftBadge } from '@parkio/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router-dom';
import { useParkioSdk } from '@/app/AppRuntimeContext';
import { useAuthStore } from '@/auth/store';
import { FriendlyApiErrorMessage } from '@/components/FriendlyApiErrorMessage';
import { adminKeys } from '@/data/keys';
import { showError, showSuccess } from '@/lib/toast';
import { AdminConfirmDialog } from './AdminConfirmDialog';

type PendingAction =
  | { type: 'suspend' }
  | { type: 'reactivate' }
  | { type: 'revoke-sessions' }
  | { type: 'resend' }
  | { type: 'revoke-session'; sessionId: string }
  | { type: 'role'; role: AdminRoleName; action: 'GRANT' | 'REVOKE' };

function roleLabel(t: (key: string) => string, role: string): string {
  const key = `roles.${role}`;
  const translated = t(key);
  return translated === key ? role : translated;
}

export function AdminUserDetailPage() {
  const { adminApi } = useParkioSdk();
  const { t } = useTranslation('admin');
  const { id = '' } = useParams();
  const roles = useAuthStore((s) => s.roles);
  const isSuper = hasSuperAdminRole(roles);
  const qc = useQueryClient();
  const [pending, setPending] = useState<PendingAction | null>(null);

  const detail = useQuery({
    queryKey: adminKeys.userDetail(id),
    queryFn: () => adminApi.getUser(id),
    enabled: Boolean(id),
  });

  const mutate = useMutation({
    mutationFn: async (reason: string) => {
      if (!pending) return;
      switch (pending.type) {
        case 'suspend':
          await adminApi.suspendUser(id, { reason });
          break;
        case 'reactivate':
          await adminApi.reactivateUser(id, { reason });
          break;
        case 'revoke-sessions':
          await adminApi.revokeAllSessions(id, { reason });
          break;
        case 'resend':
          await adminApi.resendVerification(id, { reason });
          break;
        case 'revoke-session':
          await adminApi.revokeSession(id, pending.sessionId, { reason });
          break;
        case 'role':
          // Protocol enum values GRANT/REVOKE stay as API contract — not translated.
          await adminApi.changeRole(id, { role: pending.role, action: pending.action, reason });
          break;
      }
    },
    onSuccess: async () => {
      showSuccess(t('users.toast.actionCompleted'));
      setPending(null);
      await qc.invalidateQueries({ queryKey: adminKeys.userDetail(id) });
      await qc.invalidateQueries({ queryKey: adminKeys.dashboard() });
    },
    onError: (error) => {
      showError(error instanceof Error ? error.message : t('users.toast.actionFailed'));
    },
  });

  if (detail.isPending) {
    return (
      <PageShell title={t('users.detail.title')}>
        <LoadingState label={t('common:actions.loading')} />
      </PageShell>
    );
  }
  if (detail.isError || !detail.data) {
    return (
      <PageShell title={t('users.detail.title')}>
        <FriendlyApiErrorMessage error={detail.error} />
      </PageShell>
    );
  }

  const user = detail.data.user;
  const dialogCopy = ((): { title: string; description: ReactNode; confirmLabel: string } => {
    switch (pending?.type) {
      case 'suspend':
        return {
          title: t('users.suspend.confirmationTitle'),
          description: (
            <p className="m-0">
              {t('users.suspend.confirmationDescription', { email: user.email })}
            </p>
          ),
          confirmLabel: t('users.suspend.confirmLabel'),
        };
      case 'reactivate':
        return {
          title: t('users.reactivate.confirmationTitle'),
          description: (
            <p className="m-0">
              {t('users.reactivate.confirmationDescription', { email: user.email })}
            </p>
          ),
          confirmLabel: t('users.reactivate.confirmLabel'),
        };
      case 'revoke-sessions':
        return {
          title: t('users.revokeSessions.confirmationTitle'),
          description: <p className="m-0">{t('users.revokeSessions.confirmationDescription')}</p>,
          confirmLabel: t('users.revokeSessions.confirmLabel'),
        };
      case 'resend':
        return {
          title: t('users.resendVerification.confirmationTitle'),
          description: <p className="m-0">{t('users.resendVerification.confirmationDescription')}</p>,
          confirmLabel: t('users.resendVerification.confirmLabel'),
        };
      case 'revoke-session':
        return {
          title: t('users.revokeSession.confirmationTitle'),
          description: <p className="m-0">{t('users.revokeSession.confirmationDescription')}</p>,
          confirmLabel: t('users.revokeSession.confirmLabel'),
        };
      case 'role': {
        const displayRole = roleLabel(t, pending.role);
        const actionVerb =
          pending.action === 'GRANT'
            ? t('users.roleChange.actionGrant')
            : t('users.roleChange.actionRevoke');
        return {
          title:
            pending.action === 'GRANT'
              ? t('users.roleChange.grantTitle', { role: displayRole })
              : t('users.roleChange.revokeTitle', { role: displayRole }),
          description: (
            <p className="m-0">
              {t('users.roleChange.description', {
                action: actionVerb,
                role: displayRole,
                email: user.email,
              })}
            </p>
          ),
          confirmLabel:
            pending.action === 'GRANT'
              ? t('users.roleChange.grantConfirmLabel')
              : t('users.roleChange.revokeConfirmLabel'),
        };
      }
      default:
        return { title: '', description: null, confirmLabel: t('common.confirm') };
    }
  })();

  return (
    <PageShell title={user.email}>
      <p className="mb-lg mt-0 font-mono text-body-sm text-on-surface-variant">
        <Link to="/admin/users" className="text-primary no-underline hover:underline">
          {t('users.detail.breadcrumbUsers')}
        </Link>{' '}
        / {user.id}
      </p>
      <div className="flex flex-col gap-lg">
        <Card title={t('users.detail.overview')}>
          <dl className="grid grid-cols-1 gap-sm sm:grid-cols-2">
            <div>
              <dt className="text-label-md text-on-surface-variant">{t('users.detail.status')}</dt>
              <dd className="m-0 mt-xs">
                <SoftBadge tone={user.status === 'ACTIVE' ? 'success' : 'warning'}>
                  {t(`status.${user.status}`)}
                </SoftBadge>
              </dd>
            </div>
            <div>
              <dt className="text-label-md text-on-surface-variant">{t('users.detail.emailVerified')}</dt>
              <dd className="m-0 mt-xs">{user.emailVerified ? t('common.yes') : t('common.no')}</dd>
            </div>
            <div>
              <dt className="text-label-md text-on-surface-variant">{t('users.detail.registered')}</dt>
              <dd className="m-0 mt-xs">{new Date(user.createdAt).toLocaleString()}</dd>
            </div>
            <div>
              <dt className="text-label-md text-on-surface-variant">{t('users.detail.activeSessions')}</dt>
              <dd className="m-0 mt-xs">{user.activeSessionCount}</dd>
            </div>
          </dl>
          <div className="mt-md flex flex-wrap gap-sm">
            {user.status !== 'SUSPENDED' ? (
              <Button type="button" onClick={() => setPending({ type: 'suspend' })}>
                {t('users.suspend.button')}
              </Button>
            ) : (
              <Button type="button" onClick={() => setPending({ type: 'reactivate' })}>
                {t('users.reactivate.button')}
              </Button>
            )}
            <Button type="button" variant="secondary" onClick={() => setPending({ type: 'revoke-sessions' })}>
              {t('users.revokeSessions.button')}
            </Button>
            {!user.emailVerified ? (
              <Button type="button" variant="ghost" onClick={() => setPending({ type: 'resend' })}>
                {t('users.resendVerification.button')}
              </Button>
            ) : null}
          </div>
        </Card>

        <Card title={t('users.detail.rolesTitle')}>
          <div className="flex flex-wrap gap-xs">
            {user.roles.map((role) => (
              <SoftBadge key={role} tone="primary">
                {roleLabel(t, role)}
              </SoftBadge>
            ))}
          </div>
          <div className="mt-md flex flex-wrap gap-sm">
            {(['MODERATOR', 'ADMIN', 'SUPER_ADMIN'] as AdminRoleName[]).map((role) => {
              const held = user.roles.includes(role);
              const locked = (role === 'ADMIN' || role === 'SUPER_ADMIN') && !isSuper;
              const label = roleLabel(t, role);
              return (
                <Button
                  key={role}
                  type="button"
                  variant="ghost"
                  disabled={locked}
                  onClick={() =>
                    setPending({ type: 'role', role, action: held ? 'REVOKE' : 'GRANT' })
                  }
                >
                  {held
                    ? t('users.roleChange.revokeButton', { role: label })
                    : t('users.roleChange.grantButton', { role: label })}
                </Button>
              );
            })}
          </div>
          {!isSuper ? (
            <p className="mt-sm mb-0 text-body-sm text-on-surface-variant">
              {t('users.detail.rolesSuperAdminRequired')}
            </p>
          ) : null}
        </Card>

        <Card title={t('users.detail.sessionsTitle')}>
          {detail.data.sessions.length === 0 ? (
            <EmptyState
              title={t('users.detail.sessionsEmptyTitle')}
              description={t('users.detail.sessionsEmptyDescription')}
            />
          ) : (
            <ul className="m-0 list-none space-y-sm p-0">
              {detail.data.sessions.map((session) => (
                <li
                  key={session.sessionId}
                  className="flex flex-wrap items-center justify-between gap-sm rounded-md border border-outline-variant/40 px-sm py-sm"
                >
                  <div>
                    <div className="font-mono text-body-sm">{session.sessionId}</div>
                    <div className="text-body-sm text-on-surface-variant">
                      {session.revoked
                        ? t('users.detail.sessionRevoked', {
                            reason: session.revokedReason ?? t('common.na'),
                          })
                        : t('users.detail.sessionActive')}{' '}
                      ·{' '}
                      {t('users.detail.sessionExpires', {
                        expiresAt: new Date(session.expiresAt).toLocaleString(),
                      })}
                    </div>
                  </div>
                  {!session.revoked ? (
                    <Button
                      type="button"
                      variant="ghost"
                      onClick={() => setPending({ type: 'revoke-session', sessionId: session.sessionId })}
                    >
                      {t('users.revokeSession.button')}
                    </Button>
                  ) : null}
                </li>
              ))}
            </ul>
          )}
        </Card>

        <Card title={t('users.detail.auditTitle')}>
          {detail.data.recentAuditEvents.length === 0 ? (
            <EmptyState
              title={t('users.detail.auditEmptyTitle')}
              description={t('users.detail.auditEmptyDescription')}
            />
          ) : (
            <ul className="m-0 list-none space-y-sm p-0">
              {detail.data.recentAuditEvents.map((event) => (
                <li key={event.id} className="border-b border-outline-variant/30 py-sm text-body-sm">
                  <div className="font-semibold">{event.actionType}</div>
                  <div className="text-on-surface-variant">
                    {new Date(event.occurredAt).toLocaleString()} · {event.result}
                    {event.reason ? ` · ${event.reason}` : ''}
                  </div>
                </li>
              ))}
            </ul>
          )}
        </Card>
      </div>

      <AdminConfirmDialog
        open={pending != null}
        title={dialogCopy.title}
        description={dialogCopy.description}
        confirmLabel={dialogCopy.confirmLabel}
        busy={mutate.isPending}
        onCancel={() => setPending(null)}
        onConfirm={(reason) => mutate.mutate(reason)}
      />
    </PageShell>
  );
}
