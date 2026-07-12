export const WAITLIST_SOURCE = 'parkio.dev-landing';
export const WAITLIST_ROLES = ['driver', 'tester', 'partner'] as const;

export type WaitlistRole = (typeof WAITLIST_ROLES)[number];

export function isWaitlistRole(value: string): value is WaitlistRole {
  return WAITLIST_ROLES.includes(value as WaitlistRole);
}
