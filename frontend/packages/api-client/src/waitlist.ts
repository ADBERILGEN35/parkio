import type { AxiosInstance } from 'axios';
import type {
  WaitlistAdminCounts,
  WaitlistAdminListParams,
  WaitlistAdminPage,
} from '@parkio/types';

export const WAITLIST_SOURCE = 'parkio.dev-landing';
export const WAITLIST_ROLES = ['driver', 'tester', 'partner'] as const;

export type WaitlistRole = (typeof WAITLIST_ROLES)[number];

export interface WaitlistPayload {
  fullName?: string;
  email: string;
  city?: string;
  role?: WaitlistRole;
  consentTimestamp: string;
  source: typeof WAITLIST_SOURCE;
  locale?: 'tr' | 'en';
}

export interface WaitlistResult {
  status: 'accepted' | 'confirmed' | 'withdrawn';
}

function toQuery(params: Record<string, string | number | boolean | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === '') continue;
    search.set(key, String(value));
  }
  const q = search.toString();
  return q ? `?${q}` : '';
}

export function isWaitlistRole(value: string): value is WaitlistRole {
  return WAITLIST_ROLES.includes(value as WaitlistRole);
}

export function createWaitlistApi(client: AxiosInstance) {
  return {
    submit(body: WaitlistPayload): Promise<WaitlistResult> {
      return client.post<WaitlistResult>('/waitlist', body).then((r) => r.data);
    },
    confirm(token: string): Promise<WaitlistResult> {
      return client.post<WaitlistResult>('/waitlist/confirm', { token }).then((r) => r.data);
    },
    withdraw(token: string): Promise<WaitlistResult> {
      return client.post<WaitlistResult>('/waitlist/withdraw', { token }).then((r) => r.data);
    },
    resend(email: string): Promise<WaitlistResult> {
      return client
        .post<WaitlistResult>('/waitlist/resend', { email, source: WAITLIST_SOURCE })
        .then((r) => r.data);
    },

    /** ADMIN/SUPER_ADMIN — counts for notification-list subscriptions. */
    adminSummary(): Promise<WaitlistAdminCounts> {
      return client.get<WaitlistAdminCounts>('/waitlist/admin/summary').then((r) => r.data);
    },

    /** ADMIN/SUPER_ADMIN — paginated operator list (no tokens/hashes). */
    adminList(params: WaitlistAdminListParams = {}): Promise<WaitlistAdminPage> {
      return client
        .get<WaitlistAdminPage>(
          `/waitlist/admin${toQuery(params as Record<string, string | number | boolean | undefined>)}`,
        )
        .then((r) => r.data);
    },

    /**
     * ADMIN/SUPER_ADMIN — confirmed-only CSV export.
     * Returns raw CSV text; does not log the body.
     */
    exportConfirmedCsv(params: { createdFrom?: string; createdTo?: string } = {}): Promise<string> {
      return client
        .get<string>(`/waitlist/export${toQuery(params)}`, {
          responseType: 'text',
          headers: { Accept: 'text/csv' },
        })
        .then((r) => r.data);
    },
  };
}

export type WaitlistApi = ReturnType<typeof createWaitlistApi>;
