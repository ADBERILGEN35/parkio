export {
  createApiClient,
  setRefreshHandler,
  DEFAULT_API_BASE_URL,
  type ApiClientOptions,
} from './client';
export { createAuthApi, type AuthApi } from './auth';
export { createUsersApi, type UsersApi } from './users';
export { createParkingApi, type ParkingApi } from './parking';
export { createMediaApi, type MediaApi } from './media';
export { createNotificationsApi, type NotificationsApi } from './notifications';
export { createGamificationApi, type GamificationApi } from './gamification';
export { createModerationApi, type ModerationApi } from './moderation';
export { createAnalyticsApi, type AnalyticsApi } from './analytics';
export {
  createIdempotencyKey,
  IDEMPOTENCY_HEADER,
} from './idempotency';
export { CORRELATION_HEADER, createCorrelationId } from './correlation';
export {
  MemoryTokenStorage,
  type TokenStorage,
  type StoredTokens,
} from './token-storage';
export {
  ParkioApiError,
  AccountNotActiveError,
  AccountNotVerifiedError,
  ForbiddenError,
  RateLimitError,
  UserStatusUnavailableError,
  UnauthorizedError,
  parseApiError,
  toParkioError,
  isParkioApiError,
  getAxiosParkioError,
} from './errors';
