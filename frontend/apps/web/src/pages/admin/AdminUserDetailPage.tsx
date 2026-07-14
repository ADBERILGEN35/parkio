import type { AdminRoleName } from '@parkio/types';
import { hasSuperAdminRole } from '@parkio/types';
import { Button, Card, EmptyState, LoadingState, PageShell, SoftBadge } from '@parkio/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { adminApi } from '@/api';
import { useAuthStore } from '@/auth/store';
import { FriendlyApiErrorMessage } from '@/components/FriendlyApiErrorMessage';
import { showError, showSuccess } from '@/lib/toast';
import { AdminConfirmDialog } from './AdminConfirmDialog';

type PendingAction =
  | { type: 'suspend' }
  | { type: 'reactivate' }
  | { type: 'revoke-sessions' }
  | { type: 'resend' }
  | { type: 'revoke-session'; sessionId: string }
  | { type: 'role'; role: AdminRoleName; action: 'GRANT' | 'REVOKE' };

export function AdminUserDetailPage() {
  const { id = '' } = useParams();
  const roles = useAuthStore((s) => s.roles);
  const isSuper = hasSuperAdminRole(roles);
  const qc = useQueryClient();
  const [pending, setPending] = useState<PendingAction | null>(null);

  const detail = useQuery({
    queryKey: ['admin', 'users', id],
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
          await adminApi.changeRole(id, { role: pending.role, action: pending.action, reason });
          break;
      }
    },
    onSuccess: async () => {
      showSuccess('Admin action completed');
      setPending(null);
      await qc.invalidateQueries({ queryKey: ['admin', 'users', id] });
      await qc.invalidateQueries({ queryKey: ['admin', 'dashboard'] });
    },
    onError: (error) => {
      showError(error instanceof Error ? error.message : 'Action failed');
    },
  });

  if (detail.isPending) {
    return (
      <PageShell title="User">
        <LoadingState />
      </PageShell>
    );
  }
  if (detail.isError || !detail.data) {
    return (
      <PageShell title="User">
        <FriendlyApiErrorMessage error={detail.error} />
      </PageShell>
    );
  }

  const user = detail.data.user;
  const dialogCopy = (() => {
    switch (pending?.type) {
      case 'suspend':
        return {
          title: 'Suspend account',
          description: (
            <p className="m-0">
              Suspend <strong>{user.email}</strong>? Login and refresh will fail; active sessions are revoked.
            </p>
          ),
          confirmLabel: 'Suspend',
        };
      case 'reactivate':
        return {
          title: 'Reactivate account',
          description: (
            <p className="m-0">
              Reactivate <strong>{user.email}</strong>? Previous refresh tokens stay revoked.
            </p>
          ),
          confirmLabel: 'Reactivate',
        };
      case 'revoke-sessions':
        return {
          title: 'Revoke all sessions',
          description: <p className="m-0">Invalidate every active refresh token for this user.</p>,
          confirmLabel: 'Revoke sessions',
        };
      case 'resend':
        return {
          title: 'Resend verification',
          description: <p className="m-0">Send a new verification email if the account is pending.</p>,
          confirmLabel: 'Resend',
        };
      case 'revoke-session':
        return {
          title: 'Revoke session',
          description: <p className="m-0">Revoke a single refresh-token session.</p>,
          confirmLabel: 'Revoke',
        };
      case 'role':
        return {
          title: `${pending.action} ${pending.role}`,
          description: (
            <p className="m-0">
              {pending.action} role <strong>{pending.role}</strong> for {user.email}.
            </p>
          ),
          confirmLabel: pending.action === 'GRANT' ? 'Grant role' : 'Revoke role',
        };
      default:
        return { title: '', description: null, confirmLabel: 'Confirm' };
    }
  })();

  return (
    <PageShell title={user.email}>
      <p className="mb-lg mt-0 font-mono text-body-sm text-on-surface-variant">
        <Link to="/admin/users" className="text-primary no-underline hover:underline">
          Users
        </Link>{' '}
        / {user.id}
      </p>
      <div className="flex flex-col gap-lg">
        <Card title="Overview">
          <dl className="grid grid-cols-1 gap-sm sm:grid-cols-2">
            <div>
              <dt className="text-label-md text-on-surface-variant">Status</dt>
              <dd className="m-0 mt-xs">
                <SoftBadge tone={user.status === 'ACTIVE' ? 'success' : 'warning'}>{user.status}</SoftBadge>
              </dd>
            </div>
            <div>
              <dt className="text-label-md text-on-surface-variant">Email verified</dt>
              <dd className="m-0 mt-xs">{user.emailVerified ? 'Yes' : 'No'}</dd>
            </div>
            <div>
              <dt className="text-label-md text-on-surface-variant">Registered</dt>
              <dd className="m-0 mt-xs">{new Date(user.createdAt).toLocaleString()}</dd>
            </div>
            <div>
              <dt className="text-label-md text-on-surface-variant">Active sessions</dt>
              <dd className="m-0 mt-xs">{user.activeSessionCount}</dd>
            </div>
          </dl>
          <div className="mt-md flex flex-wrap gap-sm">
            {user.status !== 'SUSPENDED' ? (
              <Button type="button" onClick={() => setPending({ type: 'suspend' })}>
                Suspend
              </Button>
            ) : (
              <Button type="button" onClick={() => setPending({ type: 'reactivate' })}>
                Reactivate
              </Button>
            )}
            <Button type="button" variant="secondary" onClick={() => setPending({ type: 'revoke-sessions' })}>
              Revoke all sessions
            </Button>
            {!user.emailVerified ? (
              <Button type="button" variant="ghost" onClick={() => setPending({ type: 'resend' })}>
                Resend verification
              </Button>
            ) : null}
          </div>
        </Card>

        <Card title="Roles">
          <div className="flex flex-wrap gap-xs">
            {user.roles.map((role) => (
              <SoftBadge key={role} tone="primary">
                {role}
              </SoftBadge>
            ))}
          </div>
          <div className="mt-md flex flex-wrap gap-sm">
            {(['MODERATOR', 'ADMIN', 'SUPER_ADMIN'] as AdminRoleName[]).map((role) => {
              const held = user.roles.includes(role);
              const locked = (role === 'ADMIN' || role === 'SUPER_ADMIN') && !isSuper;
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
                  {held ? `Revoke ${role}` : `Grant ${role}`}
                </Button>
              );
            })}
          </div>
          {!isSuper ? (
            <p className="mt-sm mb-0 text-body-sm text-on-surface-variant">
              Granting or revoking ADMIN / SUPER_ADMIN requires SUPER_ADMIN.
            </p>
          ) : null}
        </Card>

        <Card title="Sessions">
          {detail.data.sessions.length === 0 ? (
            <EmptyState title="No sessions listed" description="No refresh-token sessions to show." />
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
                      {session.revoked ? `Revoked (${session.revokedReason ?? 'n/a'})` : 'Active refresh session'}{' '}
                      · expires {new Date(session.expiresAt).toLocaleString()}
                    </div>
                  </div>
                  {!session.revoked ? (
                    <Button
                      type="button"
                      variant="ghost"
                      onClick={() => setPending({ type: 'revoke-session', sessionId: session.sessionId })}
                    >
                      Revoke
                    </Button>
                  ) : null}
                </li>
              ))}
            </ul>
          )}
        </Card>

        <Card title="Administrative history">
          {detail.data.recentAuditEvents.length === 0 ? (
            <EmptyState title="No audit events" description="No admin actions recorded for this user yet." />
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
