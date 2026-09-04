export {
  createApiClient,
  setRefreshHandler,
  refreshSession,
  isRefreshInFlight,
  type ApiClientOptions,
} from './client';
export { createAuthApi, type AuthApi } from './auth';
export { createPublicExploreApi, type PublicExploreApi } from './public-explore';
export { createUsersApi, type UsersApi } from './users';
export { createParkingApi, type ParkingApi } from './parking';
export { createMediaApi, type MediaApi, type MediaFilePart, type UploadMediaOptions } from './media';
export { createNotificationsApi, type NotificationsApi } from './notifications';
export { createGamificationApi, type GamificationApi } from './gamification';
export { createModerationApi, type ModerationApi } from './moderation';
export { createAnalyticsApi, type AnalyticsApi } from './analytics';
export { createAdminApi, type AdminApi } from './admin';
export { createGeocodingApi, type GeocodingApi } from './geocoding';
export { createPlacesApi, type PlacesApi } from './places';
export {
  createWaitlistApi,
  isWaitlistRole,
  WAITLIST_ROLES,
  WAITLIST_SOURCE,
  type WaitlistApi,
  type WaitlistPayload,
  type WaitlistResult,
  type WaitlistRole,
} from './waitlist';
export {
  createIdempotencyKey,
  IDEMPOTENCY_HEADER,
} from './idempotency';
export {
  CORRELATION_HEADER,
  createCorrelationId,
  createRequestId,
  type CorrelationId,
  type RequestId,
} from './correlation';
export type {
  SdkOperationContext,
  TelemetryAttributes,
  TelemetryAttributeValue,
} from './core-contracts';
export type {
  LoggerPort,
  MetricsPort,
  ObservabilityPorts,
  SdkCounterMeasurement,
  SdkDurationMeasurement,
  SdkLogEntry,
  SdkLogLevel,
  SdkSpanOptions,
  SdkSpanStatus,
  TracerPort,
  TraceSpanPort,
} from './observability';
export {
  MemoryTokenStorage,
  type TokenStorage,
  type StoredTokens,
} from './token-storage';
export {
  ParkioApiError,
  ParkioSdkError,
  AccountNotActiveError,
  AccountNotVerifiedError,
  ForbiddenError,
  RateLimitError,
  UserStatusUnavailableError,
  UnauthorizedError,
  ValidationError,
  NotFoundError,
  ConflictError,
  ServerError,
  NetworkError,
  TimeoutError,
  CancellationError,
  ContractValidationError,
  UnknownSdkError,
  SDK_ERROR_KINDS,
  SERIALIZED_PARKIO_ERROR_VERSION,
  parseApiError,
  toParkioError,
  toSdkError,
  classifySdkError,
  serializeParkioError,
  deserializeParkioError,
  isParkioSdkError,
  isParkioApiError,
  getAxiosParkioError,
  type ApiErrorMappingOptions,
  type ParkioApiErrorOptions,
  type SdkErrorContext,
  type SdkErrorOptions,
  type SdkErrorClassification,
  type SdkErrorKind,
  type SerializedParkioError,
  type SerializedParkioErrorName,
  type TimeoutErrorOptions,
} from './errors';
