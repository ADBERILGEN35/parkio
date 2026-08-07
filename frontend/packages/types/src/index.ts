export * from './api-error';
export * from './locale';
export * from './auth';
export * from './parking';
export * from './municipal';
export * from './parking-session-lifecycle';
export * from './media';
export * from './user';
export * from './notification';
export * from './gamification';
export * from './moderation';
export * from './analytics';
export * from './geocoding';
export * from './destination';
export * from './saved-place';
export * from './favourite';
export * from './recent';
export * from './destination-search';
export * from './quick-action';
export * from './recommendation';
export * from './admin';
export * from './waitlist';

export const PRIVILEGED_ROLES = ['MODERATOR', 'ADMIN', 'SUPER_ADMIN'] as const;

export type PrivilegedRole = (typeof PRIVILEGED_ROLES)[number];

export function hasPrivilegedRole(roles: string[]): boolean {
  return roles.some((role) => PRIVILEGED_ROLES.includes(role as PrivilegedRole));
}

/** True for ADMIN or SUPER_ADMIN (platform administration). */
export function hasAdminRole(roles: string[]): boolean {
  return roles.includes('ADMIN') || roles.includes('SUPER_ADMIN');
}

export function hasSuperAdminRole(roles: string[]): boolean {
  return roles.includes('SUPER_ADMIN');
}
