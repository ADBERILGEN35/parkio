import type { AxiosInstance } from 'axios';
import type {
  CreateAppealRequest,
  CreateReportRequest,
  ModerationAppeal,
  ModerationCase,
  ModerationReport,
  ModerationStatus,
  ResolveAppealRequest,
  ResolveCaseRequest,
} from '@parkio/types';

/**
 * Moderation endpoints. The moderator/admin functions require a MODERATOR or
 * ADMIN role — the gateway and the service both enforce it (403 FORBIDDEN).
 */
export function createModerationApi(client: AxiosInstance) {
  return {
    /** 409 DUPLICATE_REPORT when the same target was already reported for the same reason. */
    createReport(request: CreateReportRequest): Promise<ModerationReport> {
      return client.post<ModerationReport>('/moderation/reports', request).then((r) => r.data);
    },

    getMyReports(): Promise<ModerationReport[]> {
      return client.get<ModerationReport[]>('/moderation/reports/me').then((r) => r.data);
    },

    /**
     * Only a RESOLVED case that targets the appealing user can be appealed —
     * 404 CASE_NOT_FOUND otherwise, 409 CASE_NOT_RESOLVED / DUPLICATE_APPEAL.
     */
    createAppeal(request: CreateAppealRequest): Promise<ModerationAppeal> {
      return client.post<ModerationAppeal>('/moderation/appeals', request).then((r) => r.data);
    },

    // --- Moderator/admin (MODERATOR or ADMIN role required) ---

    /** Recent cases; `status` is the only filter the backend supports. */
    getModerationCases(status?: ModerationStatus): Promise<ModerationCase[]> {
      return client
        .get<ModerationCase[]>('/moderation/cases', {
          params: status === undefined ? undefined : { status },
        })
        .then((r) => r.data);
    },

    getModerationCase(caseId: string): Promise<ModerationCase> {
      return client.get<ModerationCase>(`/moderation/cases/${caseId}`).then((r) => r.data);
    },

    /** Assigns the case to the calling moderator; 409 INVALID_CASE_STATE if terminal. */
    assignModerationCase(caseId: string): Promise<ModerationCase> {
      return client.post<ModerationCase>(`/moderation/cases/${caseId}/assign`).then((r) => r.data);
    },

    /** 409 INVALID_CASE_STATE when the case is already resolved/rejected. */
    resolveModerationCase(caseId: string, request: ResolveCaseRequest): Promise<ModerationCase> {
      return client
        .post<ModerationCase>(`/moderation/cases/${caseId}/resolve`, request)
        .then((r) => r.data);
    },

    /** Recent appeals in every status — the backend takes no filter params. */
    getModerationAppeals(): Promise<ModerationAppeal[]> {
      return client.get<ModerationAppeal[]>('/moderation/appeals').then((r) => r.data);
    },

    /** 409 INVALID_APPEAL_STATE when the appeal is not OPEN anymore. */
    resolveModerationAppeal(
      appealId: string,
      request: ResolveAppealRequest,
    ): Promise<ModerationAppeal> {
      return client
        .post<ModerationAppeal>(`/moderation/appeals/${appealId}/resolve`, request)
        .then((r) => r.data);
    },
  };
}

export type ModerationApi = ReturnType<typeof createModerationApi>;
