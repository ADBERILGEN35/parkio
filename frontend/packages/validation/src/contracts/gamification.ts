import { z } from 'zod';
import {
  POINT_DIRECTIONS,
  POINT_SOURCE_TYPES,
  type GamificationAccessPolicy,
  type GamificationProgress,
  type LeaderboardEntry,
  type LevelRule,
  type LevelStanding,
  type PointsSummary,
  type PointTransactionEntry,
} from '@parkio/types';
import { instantSchema, integerSchema, nonNegativeIntegerSchema, uuidSchema } from './primitives';

export const gamificationProgressResponseSchema = z
  .object({
    userId: uuidSchema,
    totalPoints: integerSchema,
    currentLevel: nonNegativeIntegerSchema,
    updatedAt: instantSchema,
  })
  .strip() satisfies z.ZodType<GamificationProgress>;

export const pointTransactionEntryResponseSchema = z
  .object({
    sourceType: z.enum(POINT_SOURCE_TYPES),
    direction: z.enum(POINT_DIRECTIONS),
    points: integerSchema,
    relatedSpotId: uuidSchema.nullable(),
    createdAt: instantSchema,
  })
  .strip() satisfies z.ZodType<PointTransactionEntry>;

export const pointsSummaryResponseSchema = z
  .object({
    userId: uuidSchema,
    totalPoints: integerSchema,
    recentTransactions: z.array(pointTransactionEntryResponseSchema),
  })
  .strip() satisfies z.ZodType<PointsSummary>;

export const levelStandingResponseSchema = z
  .object({
    userId: uuidSchema,
    currentLevel: nonNegativeIntegerSchema,
    totalPoints: integerSchema,
    currentLevelMinPoints: integerSchema,
    nextLevelMinPoints: integerSchema.nullable(),
    pointsToNextLevel: integerSchema.nullable(),
  })
  .strip() satisfies z.ZodType<LevelStanding>;

export const gamificationAccessPolicyResponseSchema = z
  .object({
    userId: uuidSchema,
    currentLevel: nonNegativeIntegerSchema,
    searchRadiusMeters: nonNegativeIntegerSchema,
    resultLimit: nonNegativeIntegerSchema,
    dailyViewLimit: nonNegativeIntegerSchema,
    verifiedSpotPriority: z.boolean(),
    notificationPriority: z.boolean(),
  })
  .strip() satisfies z.ZodType<GamificationAccessPolicy>;

export const levelRuleResponseSchema = z
  .object({
    level: nonNegativeIntegerSchema,
    minPoints: integerSchema,
    maxPoints: integerSchema.nullable(),
    searchRadiusMeters: nonNegativeIntegerSchema,
    resultLimit: nonNegativeIntegerSchema,
    dailyViewLimit: nonNegativeIntegerSchema,
    verifiedSpotPriority: z.boolean(),
    notificationPriority: z.boolean(),
  })
  .strip() satisfies z.ZodType<LevelRule>;

export const leaderboardEntryResponseSchema = z
  .object({
    rank: nonNegativeIntegerSchema,
    userId: uuidSchema,
    totalPoints: integerSchema,
    currentLevel: nonNegativeIntegerSchema,
  })
  .strip() satisfies z.ZodType<LeaderboardEntry>;

export const levelRuleListResponseSchema = z.array(levelRuleResponseSchema);
export const leaderboardResponseSchema = z.array(leaderboardEntryResponseSchema);
