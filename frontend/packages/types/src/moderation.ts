/**
 * Moderation contracts — mirrors moderation-service presentation DTOs and domain
 * enums exactly. Covers user-facing reports/appeals and moderator/admin case
 * management.
 */

/** Mirrors `ModerationTargetType` — what a report/case is about. */
export const MODERATION_TARGET_TYPES = ['PARKING_SPOT', 'USER', 'MEDIA'] as const;

export type ModerationTargetType = (typeof MODERATION_TARGET_TYPES)[number];

/** Mirrors `ModerationReason` — why a target is being reported. */
export const MODERATION_REASONS = [
  'FAKE_PHOTO',
  'DUPLICATE_PHOTO',
  'OLD_PHOTO',
  'WRONG_LOCATION',
  'NOT_A_PARKING_SPOT',
  'ILLEGAL_OR_RISKY',
  'WRONG_VEHICLE_SIZE',
  'PRIVATE_PROPERTY',
  'SPAM_BEHAVIOR',
  'ABUSE_REPORT',
] as const;

export type ModerationReason = (typeof MODERATION_REASONS)[number];

/** Mirrors `AppealStatus` — lifecycle of an appeal. */
export const APPEAL_STATUSES = ['OPEN', 'ACCEPTED', 'REJECTED'] as const;

export type AppealStatus = (typeof APPEAL_STATUSES)[number];

/**
 * Mirrors `ModerationStatus` — lifecycle of a moderation case.
 * RESOLVED = closed with an action upheld; REJECTED = dismissed (no violation).
 */
export const MODERATION_STATUSES = ['OPEN', 'IN_REVIEW', 'RESOLVED', 'REJECTED'] as const;

export type ModerationStatus = (typeof MODERATION_STATUSES)[number];

/** Alias — the backend enum is named `ModerationStatus`. */
export type ModerationCaseStatus = ModerationStatus;

/** Mirrors `ModerationSeverity`. */
export const MODERATION_SEVERITIES = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'] as const;

export type ModerationSeverity = (typeof MODERATION_SEVERITIES)[number];

/**
 * Mirrors `ModerationAction` — the outcome applied when resolving a case.
 * APPROVE dismisses the case (status becomes REJECTED); any other action
 * upholds it (status becomes RESOLVED).
 */
export const MODERATION_ACTIONS = [
  'APPROVE',
  'REJECT',
  'MARK_FILLED',
  'MARK_RISKY',
  'REDUCE_TRUST',
  'DEDUCT_POINTS',
  'SUSPEND_USER',
  'RESTORE_USER',
] as const;

export type ModerationAction = (typeof MODERATION_ACTIONS)[number];

/** Backend `@Size(max = 2000)` on report description and appeal/resolution notes. */
export const REPORT_DESCRIPTION_MAX_LENGTH = 2000;
export const APPEAL_NOTE_MAX_LENGTH = 2000;
export const RESOLUTION_NOTE_MAX_LENGTH = 2000;

/** Mirrors `CreateReportRequest` — the reporter is the authenticated user. */
export interface CreateReportRequest {
  targetType: ModerationTargetType;
  targetId: string;
  reason: ModerationReason;
  description?: string | null;
}

/**
 * Mirrors `ReportResponse`. `caseId` is set only when the report opened (or was
 * linked to) a moderation case — "serious" reasons open one immediately. There is
 * no status field on a report itself.
 */
export interface ModerationReport {
  id: string;
  reporterUserId: string;
  targetType: ModerationTargetType;
  targetId: string;
  reason: ModerationReason;
  description: string | null;
  caseId: string | null;
  createdAt: string;
}

/** Backend DTO name alias. */
export type ReportResponse = ModerationReport;

/** Mirrors `CreateAppealRequest` — the appellant is the authenticated user. */
export interface CreateAppealRequest {
  caseId: string;
  note?: string | null;
}

/** Mirrors `AppealResponse`. Resolver fields are null until a moderator resolves it. */
export interface ModerationAppeal {
  id: string;
  appealUserId: string;
  caseId: string;
  note: string | null;
  status: AppealStatus;
  resolverModeratorId: string | null;
  resolutionNote: string | null;
  createdAt: string;
  resolvedAt: string | null;
}

/** Backend DTO name alias. */
export type AppealResponse = ModerationAppeal;

/**
 * Mirrors `CaseResponse` (moderator/admin only). `assignedModeratorId` is null
 * until assigned; resolution fields are null until the case is closed.
 */
export interface ModerationCase {
  id: string;
  targetType: ModerationTargetType;
  targetId: string;
  reason: ModerationReason;
  severity: ModerationSeverity;
  status: ModerationStatus;
  assignedModeratorId: string | null;
  reportCount: number;
  resolutionAction: ModerationAction | null;
  resolutionNote: string | null;
  openedAt: string;
  updatedAt: string;
  resolvedAt: string | null;
}

/** Backend DTO name alias. */
export type CaseResponse = ModerationCase;

/** Mirrors `ResolveCaseRequest` — action required, note optional (max 2000). */
export interface ResolveCaseRequest {
  action: ModerationAction;
  note?: string | null;
}

/** Mirrors `ResolveAppealRequest` — accepted required, note optional (max 2000). */
export interface ResolveAppealRequest {
  accepted: boolean;
  note?: string | null;
}
