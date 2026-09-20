export { ActiveTimeTracker } from './activeTime';
export {
  ProductAnalyticsClient,
  createMemoryStorage,
  createWebLocalStorage,
} from './client';
export { LocalCaptureTransport, NullTransport } from './localCapture';
export { normalizeAnalyticsScreenName } from './normalizeScreen';
export { PostHogHttpTransport } from './posthogHttp';
export type {
  AnalyticsConsentState,
  AnalyticsStorage,
  AnalyticsTransport,
  CapturedAnalyticsEvent,
  ProductAnalyticsConfig,
  ScreenActiveTimeSnapshot,
} from './types';
