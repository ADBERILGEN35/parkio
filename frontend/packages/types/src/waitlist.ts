export const WAITLIST_SOURCE_VALUE = 'parkio.dev-landing' as const;

export const WAITLIST_ROLE_VALUES = ['driver', 'tester', 'partner'] as const;

export type WaitlistRoleValue = (typeof WAITLIST_ROLE_VALUES)[number];

export type WaitlistSubscriptionStatus = 'PENDING' | 'CONFIRMED' | 'WITHDRAWN';

export interface SubmitWaitlistRequest {
  /** Optional until gateway `full-name-required` is flipped; marketing always sends it. */
  fullName?: string | null;
  email: string;
  consentTimestamp: string;
  /** CL-F18: required by the gateway unless its `consent-required` compatibility flag is off. */
  consent?: boolean;
  /** CL-F18: id of the consent text shown, e.g. `waitlist-consent-v1`; only registered ids are accepted. */
  consentTextVersion?: string;
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
  fullName: string | null;
  status: WaitlistSubscriptionStatus;
  locale: string | null;
  source: string | null;
  createdAt: string;
  confirmedAt: string | null;
  withdrawnAt: string | null;
  /** CL-F18: gateway receipt time of the consented submission. */
  consentTimestamp?: string;
  /** CL-F18: consent text version, or `legacy-unversioned` / `unversioned-client`. */
  consentTextVersion?: string;
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
