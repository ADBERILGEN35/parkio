import type { AxiosInstance } from 'axios';
import type {
  AdminAuditEvent,
  AdminAuditListParams,
  AdminDashboard,
  AdminPage,
  AdminReasonBody,
  AdminRoleChangeBody,
  AdminSecuritySummary,
  AdminSession,
  AdminUserDetail,
  AdminUserListParams,
  AdminUserSummary,
} from '@parkio/types';

function toQuery(params: Record<string, string | number | boolean | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === '') continue;
    search.set(key, String(value));
  }
  const q = search.toString();
  return q ? `?${q}` : '';
}

export function createAdminApi(client: AxiosInstance) {
  return {
    getDashboard(): Promise<AdminDashboard> {
      return client.get<AdminDashboard>('/admin/dashboard').then((r) => r.data);
    },

    listUsers(params: AdminUserListParams = {}): Promise<AdminPage<AdminUserSummary>> {
      return client
        .get<AdminPage<AdminUserSummary>>(`/admin/users${toQuery(params as Record<string, string | number | boolean | undefined>)}`)
        .then((r) => r.data);
    },

    getUser(userId: string): Promise<AdminUserDetail> {
      return client.get<AdminUserDetail>(`/admin/users/${userId}`).then((r) => r.data);
    },

    suspendUser(userId: string, body: AdminReasonBody): Promise<void> {
      return client.post(`/admin/users/${userId}/suspend`, body).then(() => undefined);
    },

    reactivateUser(userId: string, body: AdminReasonBody): Promise<void> {
      return client.post(`/admin/users/${userId}/reactivate`, body).then(() => undefined);
    },

    revokeAllSessions(userId: string, body: AdminReasonBody): Promise<void> {
      return client.post(`/admin/users/${userId}/revoke-sessions`, body).then(() => undefined);
    },

    resendVerification(userId: string, body?: Partial<AdminReasonBody>): Promise<void> {
      return client.post(`/admin/users/${userId}/resend-verification`, body ?? {}).then(() => undefined);
    },

    listSessions(userId: string): Promise<AdminSession[]> {
      return client.get<AdminSession[]>(`/admin/users/${userId}/sessions`).then((r) => r.data);
    },

    revokeSession(userId: string, sessionId: string, body: AdminReasonBody): Promise<void> {
      return client
        .delete(`/admin/users/${userId}/sessions/${sessionId}`, { data: body })
        .then(() => undefined);
    },

    changeRole(userId: string, body: AdminRoleChangeBody): Promise<void> {
      return client.post(`/admin/users/${userId}/roles`, body).then(() => undefined);
    },

    listAuditEvents(params: AdminAuditListParams = {}): Promise<AdminPage<AdminAuditEvent>> {
      return client
        .get<AdminPage<AdminAuditEvent>>(
          `/admin/audit-events${toQuery(params as Record<string, string | number | boolean | undefined>)}`,
        )
        .then((r) => r.data);
    },

    getSecuritySummary(): Promise<AdminSecuritySummary> {
      return client.get<AdminSecuritySummary>('/admin/security/summary').then((r) => r.data);
    },
  };
}

export type AdminApi = ReturnType<typeof createAdminApi>;
