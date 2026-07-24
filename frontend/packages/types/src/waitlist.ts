export const WAITLIST_SOURCE_VALUE = 'parkio.dev-landing' as const;

export const WAITLIST_ROLE_VALUES = ['driver', 'tester', 'partner'] as const;

export type WaitlistRoleValue = (typeof WAITLIST_ROLE_VALUES)[number];

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
