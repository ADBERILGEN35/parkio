export * from './api-error';
export * from './auth';
export * from './parking';
export * from './media';
export * from './user';
export * from './notification';
export * from './gamification';
export * from './moderation';
export * from './analytics';
export * from './geocoding';

export const PRIVILEGED_ROLES = ['MODERATOR', 'ADMIN'] as const;

export type PrivilegedRole = (typeof PRIVILEGED_ROLES)[number];

export function hasPrivilegedRole(roles: string[]): boolean {
  return roles.some((role) => PRIVILEGED_ROLES.includes(role as PrivilegedRole));
}
