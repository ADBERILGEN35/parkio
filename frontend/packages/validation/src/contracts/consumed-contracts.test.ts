import { describe, expect, it } from 'vitest';
import type { ZodTypeAny } from 'zod';
import {
  adminAuditListParamsSchema,
  adminAuditPageResponseSchema,
  adminDashboardResponseSchema,
  adminReasonBodySchema,
  adminRoleChangeBodySchema,
  adminSecuritySummaryResponseSchema,
  adminSessionListResponseSchema,
  adminUserDetailResponseSchema,
  adminUserListParamsSchema,
  adminUserPageResponseSchema,
} from './admin';
import {
  analyticsMetricListResponseSchema,
  analyticsOverviewResponseSchema,
  dailyAnalyticsListResponseSchema,
  parkingAnalyticsListResponseSchema,
  userAnalyticsListResponseSchema,
} from './analytics';
import {
  changePasswordRequestSchema,
  forgotPasswordRequestSchema,
  resendVerificationRequestSchema,
  resetPasswordRequestSchema,
  verifyEmailRequestSchema,
} from './auth';
import {
  gamificationAccessPolicyResponseSchema,
  gamificationProgressResponseSchema,
  leaderboardResponseSchema,
  levelRuleListResponseSchema,
  levelStandingResponseSchema,
  pointsSummaryResponseSchema,
} from './gamification';
import { geocodeSearchResponseSchema } from './geocoding';
import { uploadMediaResponseSchema } from './media';
import {
  createAppealRequestSchema,
  createReportRequestSchema,
  moderationAppealListResponseSchema,
  moderationCaseResponseSchema,
  moderationReportListResponseSchema,
  resolveAppealRequestSchema,
  resolveCaseRequestSchema,
} from './moderation';
import {
  deviceTokenResponseSchema,
  notificationListResponseSchema,
  registerDeviceTokenRequestSchema,
} from './notification';
import {
  createSpotRequestSchema,
  nearbySearchParamsContractSchema,
  spotListResponseSchema,
  spotMediaAccessUrlResponseSchema,
  verifySpotRequestSchema,
} from './parking';
import {
  profileResponseSchema,
  publicProfileResponseSchema,
  smartReturnSettingsResponseSchema,
  smartReturnTodayRequestSchema,
  updatePreferenceRequestSchema,
  updateProfileRequestSchema,
  updateSmartReturnSettingsRequestSchema,
  upsertVehicleRequestSchema,
  userPreferenceResponseSchema,
  userStatsResponseSchema,
  vehicleProfileResponseSchema,
} from './user';
import { submitWaitlistRequestSchema, waitlistAcceptedResponseSchema } from './waitlist';

const id = '8a56ef7e-69de-4f3c-8fe5-32b83d67f1b4';
const otherId = 'd431ad5a-f8ce-4be2-b4dc-248b47990b39';
const instant = '2026-07-22T12:00:00Z';

const adminUser = {
  id,
  email: 'driver@parkio.dev',
  status: 'ACTIVE',
  emailVerified: true,
  roles: ['USER'],
  createdAt: instant,
  activeSessionCount: 1,
};

const adminAudit = {
  id,
  occurredAt: instant,
  actorUserId: otherId,
  actorRoles: 'ADMIN',
  actionType: 'ADMIN_USER_SUSPENDED',
  targetResourceType: 'USER',
  targetResourceId: id,
  result: 'SUCCESS',
  reason: 'policy violation',
  correlationId: 'trace-1',
};

const consumedResponses: Array<{ name: string; schema: ZodTypeAny; payload: unknown }> = [
  {
    name: 'profile',
    schema: profileResponseSchema,
    payload: {
      id,
      authUserId: otherId,
      email: 'driver@parkio.dev',
      displayName: null,
      phoneNumber: null,
      city: 'Istanbul',
      status: 'ACTIVE',
      createdAt: instant,
    },
  },
  {
    name: 'preferences',
    schema: userPreferenceResponseSchema,
    payload: { preferredRadiusMeters: 1_000, notificationsEnabled: true, preferredLocale: 'tr' },
  },
  {
    name: 'smart return',
    schema: smartReturnSettingsResponseSchema,
    payload: {
      enabled: false,
      homeLatitude: null,
      homeLongitude: null,
      homeLabel: null,
      defaultReturnTime: null,
      reminderLeadMinutes: 15,
      lastPromptDate: null,
      todayStatus: 'UNKNOWN',
      todayExpectedReturnAt: null,
      todayReturnCheckCompletedAt: null,
      todayNotificationSentAt: null,
    },
  },
  {
    name: 'vehicle',
    schema: vehicleProfileResponseSchema,
    payload: { vehicleType: 'SEDAN', plate: '34 PK 123' },
  },
  {
    name: 'user stats',
    schema: userStatsResponseSchema,
    payload: { trustScore: 90, trustBand: 'HIGH_TRUST', totalPoints: 200, currentLevel: 2 },
  },
  {
    name: 'public profile',
    schema: publicProfileResponseSchema,
    payload: {
      userId: id,
      displayName: 'Parkio Driver',
      city: 'Istanbul',
      trustBand: 'HIGH_TRUST',
      currentLevel: 2,
      status: 'ACTIVE',
      memberSince: instant,
    },
  },
  {
    name: 'media upload',
    schema: uploadMediaResponseSchema,
    payload: {
      mediaId: id,
      status: 'READY',
      contentType: 'image/jpeg',
      fileSize: 1_024,
      claimedRegion: { x: 0.1, y: 0.1, width: 0.5, height: 0.5 },
    },
  },
  {
    name: 'notifications',
    schema: notificationListResponseSchema,
    payload: [
      {
        id,
        type: 'SYSTEM',
        channel: 'IN_APP',
        title: 'Title',
        body: 'Body',
        metadata: {},
        status: 'SENT',
        createdAt: instant,
        readAt: null,
      },
    ],
  },
  {
    name: 'device token',
    schema: deviceTokenResponseSchema,
    payload: { id, platform: 'ANDROID', active: true, createdAt: instant },
  },
  {
    name: 'gamification progress',
    schema: gamificationProgressResponseSchema,
    payload: { userId: id, totalPoints: 200, currentLevel: 2, updatedAt: instant },
  },
  {
    name: 'points summary',
    schema: pointsSummaryResponseSchema,
    payload: {
      userId: id,
      totalPoints: 200,
      recentTransactions: [
        {
          sourceType: 'PARKING_CLAIMED',
          direction: 'EARNED',
          points: 10,
          relatedSpotId: otherId,
          createdAt: instant,
        },
      ],
    },
  },
  {
    name: 'level standing',
    schema: levelStandingResponseSchema,
    payload: {
      userId: id,
      currentLevel: 2,
      totalPoints: 200,
      currentLevelMinPoints: 100,
      nextLevelMinPoints: 300,
      pointsToNextLevel: 100,
    },
  },
  {
    name: 'access policy',
    schema: gamificationAccessPolicyResponseSchema,
    payload: {
      userId: id,
      currentLevel: 2,
      searchRadiusMeters: 1_000,
      resultLimit: 20,
      dailyViewLimit: 50,
      verifiedSpotPriority: true,
      notificationPriority: true,
    },
  },
  {
    name: 'level rules',
    schema: levelRuleListResponseSchema,
    payload: [
      {
        level: 1,
        minPoints: 0,
        maxPoints: 99,
        searchRadiusMeters: 1_000,
        resultLimit: 10,
        dailyViewLimit: 20,
        verifiedSpotPriority: false,
        notificationPriority: false,
      },
    ],
  },
  {
    name: 'leaderboard',
    schema: leaderboardResponseSchema,
    payload: [{ rank: 1, userId: id, totalPoints: 200, currentLevel: 2 }],
  },
  {
    name: 'moderation reports',
    schema: moderationReportListResponseSchema,
    payload: [
      {
        id,
        reporterUserId: otherId,
        targetType: 'PARKING_SPOT',
        targetId: id,
        reason: 'WRONG_LOCATION',
        description: null,
        caseId: null,
        createdAt: instant,
      },
    ],
  },
  {
    name: 'moderation appeals',
    schema: moderationAppealListResponseSchema,
    payload: [
      {
        id,
        appealUserId: otherId,
        caseId: id,
        note: null,
        status: 'OPEN',
        resolverModeratorId: null,
        resolutionNote: null,
        createdAt: instant,
        resolvedAt: null,
      },
    ],
  },
  {
    name: 'moderation case',
    schema: moderationCaseResponseSchema,
    payload: {
      id,
      targetType: 'PARKING_SPOT',
      targetId: otherId,
      reason: 'WRONG_LOCATION',
      severity: 'MEDIUM',
      status: 'OPEN',
      assignedModeratorId: null,
      reportCount: 1,
      resolutionAction: null,
      resolutionNote: null,
      openedAt: instant,
      updatedAt: instant,
      resolvedAt: null,
    },
  },
  {
    name: 'analytics overview',
    schema: analyticsOverviewResponseSchema,
    payload: {
      totalParkingCreated: 10,
      totalParkingVerified: 8,
      totalParkingClaimed: 6,
      totalParkingRejected: 1,
      totalPointsEarned: 100,
      totalLevelUps: 3,
      totalNotificationsCreated: 20,
    },
  },
  {
    name: 'daily analytics',
    schema: dailyAnalyticsListResponseSchema,
    payload: [{ date: '2026-07-22', metricType: 'PARKING_CREATED', eventCount: 2, sumValue: 2 }],
  },
  {
    name: 'user analytics',
    schema: userAnalyticsListResponseSchema,
    payload: [{ userId: id, metricType: 'POINTS_EARNED', eventCount: 2, sumValue: 20 }],
  },
  {
    name: 'parking analytics',
    schema: parkingAnalyticsListResponseSchema,
    payload: [{ metricType: 'PARKING_CLAIMED', eventCount: 2, sumValue: 2 }],
  },
  {
    name: 'analytics metrics',
    schema: analyticsMetricListResponseSchema,
    payload: [{ metricType: 'LEVEL_UP', totalCount: 2, totalValue: 2 }],
  },
  {
    name: 'geocoding search',
    schema: geocodeSearchResponseSchema,
    payload: {
      results: [
        {
          id: 'provider-1',
          displayName: 'Istanbul',
          primary: 'Istanbul',
          secondary: 'Türkiye',
          lat: 41.0082,
          lng: 28.9784,
        },
      ],
    },
  },
  {
    name: 'owned parking spots',
    schema: spotListResponseSchema,
    payload: [
      {
        id,
        ownerUserId: otherId,
        mediaId: id,
        latitude: 41.0082,
        longitude: 28.9784,
        addressText: null,
        description: null,
        manualLocationEdited: false,
        suitableVehicleTypes: ['SEDAN'],
        parkingContext: 'STREET_PARKING',
        legalStatus: 'LEGAL',
        violationReasons: [],
        status: 'ACTIVE',
        confidenceScore: 0.9,
        verificationCount: 1,
        filledReportCount: 0,
        expiresAt: instant,
        createdAt: instant,
        updatedAt: instant,
      },
    ],
  },
  {
    name: 'spot media access URL',
    schema: spotMediaAccessUrlResponseSchema,
    payload: { spotId: id, mediaId: otherId, accessUrl: 'https://media.example/spot', expiresAt: instant },
  },
  {
    name: 'admin dashboard',
    schema: adminDashboardResponseSchema,
    payload: {
      totalUsers: 10,
      usersByStatus: { ACTIVE: 9, SUSPENDED: 1 },
      verifiedUsers: 9,
      unverifiedUsers: 1,
      registrationsToday: 1,
      registrationsLast7Days: 3,
      registrationsLast30Days: 10,
      verificationConversionRate: 0.9,
      activeSessionCount: 2,
    },
  },
  {
    name: 'admin user page',
    schema: adminUserPageResponseSchema,
    payload: { content: [adminUser], page: 0, size: 20, totalElements: 1, totalPages: 1 },
  },
  {
    name: 'admin user detail',
    schema: adminUserDetailResponseSchema,
    payload: {
      user: adminUser,
      sessions: [
        {
          sessionId: id,
          createdAt: instant,
          revoked: false,
          revokedReason: null,
          expiresAt: instant,
        },
      ],
      recentAuditEvents: [adminAudit],
    },
  },
  {
    name: 'admin sessions',
    schema: adminSessionListResponseSchema,
    payload: [
      { sessionId: id, createdAt: instant, revoked: true, revokedReason: 'LOGOUT', expiresAt: instant },
    ],
  },
  {
    name: 'admin audit page',
    schema: adminAuditPageResponseSchema,
    payload: { content: [adminAudit], page: 0, size: 20, totalElements: 1, totalPages: 1 },
  },
  {
    name: 'admin security summary',
    schema: adminSecuritySummaryResponseSchema,
    payload: {
      suspendedUsers: 1,
      pendingVerificationUsers: 2,
      activeSessionCount: 3,
      reuseDetectedSessionCount: 0,
    },
  },
  {
    name: 'waitlist accepted',
    schema: waitlistAcceptedResponseSchema,
    payload: { status: 'accepted' },
  },
];

const strictRequests: Array<{ name: string; schema: ZodTypeAny; payload: Record<string, unknown> }> = [
  { name: 'verify email', schema: verifyEmailRequestSchema, payload: { token: 'token' } },
  {
    name: 'resend verification',
    schema: resendVerificationRequestSchema,
    payload: { email: 'driver@parkio.dev', locale: 'tr' },
  },
  {
    name: 'forgot password',
    schema: forgotPasswordRequestSchema,
    payload: { email: 'driver@parkio.dev', locale: 'en' },
  },
  {
    name: 'reset password',
    schema: resetPasswordRequestSchema,
    payload: { token: 'token', newPassword: 'new-password' },
  },
  {
    name: 'change password',
    schema: changePasswordRequestSchema,
    payload: { currentPassword: 'old-password', newPassword: 'new-password' },
  },
  {
    name: 'update profile',
    schema: updateProfileRequestSchema,
    payload: { displayName: 'Parkio Driver' },
  },
  {
    name: 'update preferences',
    schema: updatePreferenceRequestSchema,
    payload: { preferredRadiusMeters: 1_000 },
  },
  {
    name: 'update smart return',
    schema: updateSmartReturnSettingsRequestSchema,
    payload: { enabled: false },
  },
  {
    name: 'smart return today',
    schema: smartReturnTodayRequestSchema,
    payload: { expectedReturnAt: instant },
  },
  { name: 'upsert vehicle', schema: upsertVehicleRequestSchema, payload: { vehicleType: 'SEDAN' } },
  {
    name: 'create report',
    schema: createReportRequestSchema,
    payload: { targetType: 'PARKING_SPOT', targetId: id, reason: 'WRONG_LOCATION' },
  },
  { name: 'create appeal', schema: createAppealRequestSchema, payload: { caseId: id } },
  { name: 'resolve case', schema: resolveCaseRequestSchema, payload: { action: 'APPROVE' } },
  { name: 'resolve appeal', schema: resolveAppealRequestSchema, payload: { accepted: true } },
  {
    name: 'nearby search',
    schema: nearbySearchParamsContractSchema,
    payload: { lat: 41.0082, lng: 28.9784 },
  },
  {
    name: 'create spot',
    schema: createSpotRequestSchema,
    payload: {
      mediaId: id,
      latitude: 41.0082,
      longitude: 28.9784,
      suitableVehicleTypes: ['SEDAN'],
      parkingContext: 'STREET_PARKING',
      legalStatus: 'LEGAL',
    },
  },
  { name: 'verify spot', schema: verifySpotRequestSchema, payload: { result: 'AVAILABLE' } },
  { name: 'admin user list', schema: adminUserListParamsSchema, payload: { page: 0 } },
  { name: 'admin audit list', schema: adminAuditListParamsSchema, payload: { page: 0 } },
  { name: 'admin reason', schema: adminReasonBodySchema, payload: { reason: 'approved reason' } },
  {
    name: 'admin role change',
    schema: adminRoleChangeBodySchema,
    payload: { role: 'MODERATOR', action: 'GRANT' },
  },
  {
    name: 'register device token',
    schema: registerDeviceTokenRequestSchema,
    payload: { token: 'push-token', platform: 'ANDROID' },
  },
  {
    name: 'submit waitlist',
    schema: submitWaitlistRequestSchema,
    payload: {
      email: 'driver@parkio.dev',
      consentTimestamp: instant,
      city: 'Istanbul',
      role: 'driver',
      source: 'parkio.dev-landing',
    },
  },
];

describe('consumed backend response coverage', () => {
  it.each(consumedResponses)('validates the $name response', ({ schema, payload }) => {
    expect(schema.safeParse(payload).success).toBe(true);
  });
});

describe('strict request coverage', () => {
  it.each(strictRequests)('rejects unknown properties for $name', ({ schema, payload }) => {
    expect(schema.safeParse(payload).success).toBe(true);
    expect(schema.safeParse({ ...payload, unexpectedContractField: true }).success).toBe(false);
  });
});
