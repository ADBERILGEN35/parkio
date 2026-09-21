import type { AxiosInstance } from 'axios';

export const WAITLIST_SOURCE = 'parkio.dev-landing';
export const WAITLIST_ROLES = ['driver', 'tester', 'partner'] as const;

export type WaitlistRole = (typeof WAITLIST_ROLES)[number];

export interface WaitlistPayload {
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
  };
}

export type WaitlistApi = ReturnType<typeof createWaitlistApi>;
