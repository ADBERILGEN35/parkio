import type { AuthRoleName, AuthUserStatus } from './auth';

export type AdminRoleName = AuthRoleName;

export type AdminAuditAction =
  | 'ADMIN_USER_SUSPENDED'
  | 'ADMIN_USER_REACTIVATED'
  | 'ADMIN_USER_SESSIONS_REVOKED'
  | 'ADMIN_USER_ROLE_GRANTED'
  | 'ADMIN_USER_ROLE_REVOKED'
  | 'ADMIN_VERIFICATION_RESENT'
  | 'ADMIN_SESSION_REVOKED'
  | 'ADMIN_BOOTSTRAP_SUPER_ADMIN';

export type AdminAuditResult = 'SUCCESS' | 'FAILURE';

type AdminSessionRevocationReason =
  | 'ROTATED'
  | 'LOGOUT'
  | 'REUSE_DETECTED'
  | 'EXPIRED_CLEANUP'
  | 'ADMIN_REVOKED'
  | 'PASSWORD_CHANGED';

export interface AdminDashboard {
  totalUsers: number;
  usersByStatus: Partial<Record<AuthUserStatus, number>>;
  verifiedUsers: number;
  unverifiedUsers: number;
  registrationsToday: number;
  registrationsLast7Days: number;
  registrationsLast30Days: number;
  verificationConversionRate: number;
  activeSessionCount: number;
}

export interface AdminUserSummary {
  id: string;
  email: string;
  status: AuthUserStatus;
  emailVerified: boolean;
  roles: AdminRoleName[];
  createdAt: string;
  activeSessionCount: number;
}

export interface AdminSession {
  sessionId: string;
  createdAt: string;
  revoked: boolean;
  revokedReason: AdminSessionRevocationReason | null;
  expiresAt: string;
}

export interface AdminAuditEvent {
  id: string;
  occurredAt: string;
  actorUserId: string;
  actorRoles: string;
  actionType: AdminAuditAction;
  targetResourceType: string;
  targetResourceId: string | null;
  result: AdminAuditResult;
  reason: string | null;
  correlationId: string | null;
}

export interface AdminUserDetail {
  user: AdminUserSummary;
  sessions: AdminSession[];
  recentAuditEvents: AdminAuditEvent[];
}

export interface AdminPage<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface AdminSecuritySummary {
  suspendedUsers: number;
  pendingVerificationUsers: number;
  activeSessionCount: number;
  reuseDetectedSessionCount: number;
}

export interface AdminUserListParams {
  q?: string;
  email?: string;
  userId?: string;
  status?: AuthUserStatus;
  emailVerified?: boolean;
  role?: AdminRoleName;
  createdFrom?: string;
  createdTo?: string;
  page?: number;
  size?: number;
  sort?: string;
}

export interface AdminAuditListParams {
  actorUserId?: string;
  targetResourceId?: string;
  actionType?: AdminAuditAction;
  result?: AdminAuditResult;
  occurredFrom?: string;
  occurredTo?: string;
  page?: number;
  size?: number;
  sort?: string;
}

export interface AdminReasonBody {
  reason: string;
}

export interface AdminRoleChangeBody {
  role: AdminRoleName;
  action: 'GRANT' | 'REVOKE';
  reason?: string | null;
}
