import {
  WAITLIST_ROLES,
  WAITLIST_SOURCE,
  isWaitlistRole,
  type WaitlistRole,
} from '@parkio/api-client';
import { waitlistApi } from '@/api';

export interface WaitlistPayload {
  email: string;
  city?: string;
  role?: WaitlistRole;
  betaUpdatesConsent: boolean;
  researchConsent: boolean;
  consentTimestamp: string;
  source: typeof WAITLIST_SOURCE;
}

export interface WaitlistResult {
  status: 'accepted';
}

export async function submitWaitlistInterest(payload: WaitlistPayload): Promise<WaitlistResult> {
  if (!payload.betaUpdatesConsent) {
    throw new Error('Consent is required to join the waitlist.');
  }

  if (payload.role && !isWaitlistRole(payload.role)) {
    throw new Error('Unsupported waitlist role.');
  }

  return waitlistApi.submit({
    email: payload.email,
    city: payload.city,
    role: payload.role,
    consentTimestamp: payload.consentTimestamp,
    source: WAITLIST_SOURCE,
  });
}

export { WAITLIST_ROLES, WAITLIST_SOURCE, isWaitlistRole, type WaitlistRole };
