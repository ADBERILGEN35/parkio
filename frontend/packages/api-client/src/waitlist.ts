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
  /** CL-F18: the gateway requires these unless its compatibility flag is off. */
  consent?: boolean;
  consentTextVersion?: string;
  source: typeof WAITLIST_SOURCE;
  locale?: 'tr' | 'en';
}

export interface WaitlistResult {
  status: 'accepted' | 'confirmed' | 'withdrawn';
}

/** Confirmed-only CSV export: the CSV text and the gateway's truncation report. */
export interface WaitlistExportResult {
  csv: string;
  /** True when more confirmed subscriptions matched than the export returned. */
  truncated: boolean;
  /** Rows that matched the filter when the export started, if the gateway reported it. */
  matchingRows?: number;
  /** The export's row limit, if the gateway reported it. */
  rowLimit?: number;
}

function numberHeader(value: unknown): number | undefined {
  if (typeof value !== 'string' || value === '') return undefined;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : undefined;
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
     * ADMIN/SUPER_ADMIN — confirmed-only CSV export, filtered by confirmation time.
     * Returns the raw CSV text with the truncation report; does not log the body.
     */
    exportConfirmedCsv(
      params: { confirmedFrom?: string; confirmedTo?: string } = {},
    ): Promise<WaitlistExportResult> {
      return client
        .get<string>(`/waitlist/export${toQuery(params)}`, {
          responseType: 'text',
          headers: { Accept: 'text/csv' },
        })
        .then((r) => ({
          csv: r.data,
          truncated: r.headers['x-parkio-export-truncated'] === 'true',
          matchingRows: numberHeader(r.headers['x-parkio-export-matching-rows']),
          rowLimit: numberHeader(r.headers['x-parkio-export-row-limit']),
        }));
    },
  };
}

export type WaitlistApi = ReturnType<typeof createWaitlistApi>;
