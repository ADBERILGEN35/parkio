import type { RecommendationReasonCode } from '@parkio/types';

/** i18n keys under map:assistant.reasons.* for SPA-06 reason codes. */
export const RECOMMENDATION_REASON_I18N: Record<RecommendationReasonCode, string> = {
  CLOSE_TO_DESTINATION: 'assistant.reasons.closeToDestination',
  LIVE_AVAILABILITY: 'assistant.reasons.liveAvailability',
  HIGH_CAPACITY: 'assistant.reasons.highCapacity',
  STATIC_INVENTORY: 'assistant.reasons.staticInventory',
  COMMUNITY_FRESH: 'assistant.reasons.communityFresh',
  INVENTORY_DEGRADED: 'assistant.reasons.inventoryDegraded',
  FAVOURITE: 'assistant.reasons.favourite',
  HIGH_CONFIDENCE: 'assistant.reasons.highConfidence',
};

export const MAX_VISIBLE_REASONS = 3;

/** SPA-05 product defaults for recommendation requests. */
export const ASSISTANT_RECOMMEND_RADIUS_METERS = 1500;
export const ASSISTANT_RECOMMEND_LIMIT = 10;
