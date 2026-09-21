export const WAITLIST_SOURCE_VALUE = 'parkio.dev-landing' as const;

export const WAITLIST_ROLE_VALUES = ['driver', 'tester', 'partner'] as const;

export type WaitlistRoleValue = (typeof WAITLIST_ROLE_VALUES)[number];

export type WaitlistSubscriptionStatus = 'PENDING' | 'CONFIRMED' | 'WITHDRAWN';

export interface SubmitWaitlistRequest {
  email: string;
  consentTimestamp: string;
  city?: string | null;
  role?: WaitlistRoleValue | null;
  source: typeof WAITLIST_SOURCE_VALUE;
}

export interface WaitlistAcceptedResponse {
  status: 'accepted';
}

/** Operator view of a notification-list subscription (not an application account). */
export interface WaitlistAdminEntry {
  id: string;
  email: string;
  status: WaitlistSubscriptionStatus;
  locale: string | null;
  source: string | null;
  createdAt: string;
  confirmedAt: string | null;
  withdrawnAt: string | null;
}

export interface WaitlistAdminCounts {
  pending: number;
  confirmed: number;
  withdrawn: number;
  total: number;
}

export interface WaitlistAdminPage {
  content: WaitlistAdminEntry[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface WaitlistAdminListParams {
  status?: WaitlistSubscriptionStatus;
  createdFrom?: string;
  createdTo?: string;
  page?: number;
  size?: number;
}
