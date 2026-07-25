/**
 * Lifecycle of a parking spot — mirrors parking-service `ParkingSpotStatus`.
 *
 * `REVIEW_FAILED` is terminal: moderation never reached a verdict within its deadline,
 * bounded validation retries were exhausted, or an approval arrived after
 * `maxPublishableAge`. It exists so a submission can never sit in "Pending review"
 * indefinitely — the owner is told the review could not be completed and can resubmit.
 */
export const PARKING_STATUSES = [
  'PENDING_VALIDATION',
  'PENDING_REVIEW',
  'ACTIVE',
  'VERIFIED',
  'SUSPICIOUS',
  'FILLED',
  'EXPIRED',
  'REJECTED',
  'REVIEW_FAILED',
] as const;

export type ParkingStatus = (typeof PARKING_STATUSES)[number];

/** Spot statuses that are still waiting on the moderation pipeline. */
export const PENDING_MODERATION_STATUSES = ['PENDING_VALIDATION', 'PENDING_REVIEW'] as const;

/**
 * Whether a spot is still awaiting a moderation verdict. Pending spots have not started
 * their advertised lifetime, so `expiresAt` is null and must never be shown as a countdown.
 */
export function isPendingModeration(status: ParkingStatus): boolean {
  return status === 'PENDING_VALIDATION' || status === 'PENDING_REVIEW';
}

/**
 * Vehicle sizes a spot can accommodate — mirrors parking-service `VehicleType`.
 * `ANY` means no size restriction. Note: this is a different enum from the
 * user-service vehicle profile `VehicleType`.
 */
export const SPOT_VEHICLE_TYPES = [
  'SEDAN',
  'HATCHBACK',
  'SUV',
  'VAN',
  'MOTORCYCLE',
  'ANY',
] as const;

export type SpotVehicleType = (typeof SPOT_VEHICLE_TYPES)[number];

/** Legal standing of parking at the spot — mirrors parking-service `LegalStatus`. */
export const LEGAL_STATUSES = ['LEGAL', 'UNCERTAIN', 'ILLEGAL_OR_RISKY'] as const;

export type LegalStatus = (typeof LEGAL_STATUSES)[number];

/** Where the spot is located — mirrors parking-service `ParkingContext`. */
export const PARKING_CONTEXTS = [
  'STREET_PARKING',
  'OPEN_PARKING_LOT',
  'INDOOR_PARKING',
  'MALL_PARKING',
  'RESIDENTIAL_AREA',
  'OFFICE_AREA',
  'UNKNOWN',
] as const;

export type ParkingContext = (typeof PARKING_CONTEXTS)[number];

/** Why a spot may be illegal/risky — mirrors parking-service `ViolationReason`. */
export const VIOLATION_REASONS = [
  'NO_PARKING_SIGN',
  'GARAGE_ENTRANCE',
  'BUS_STOP',
  'PEDESTRIAN_CROSSING',
  'FIRE_HYDRANT',
  'SIDEWALK',
  'TRAFFIC_FLOW_BLOCKING',
  'PRIVATE_PROPERTY',
  'OTHER',
] as const;

export type ViolationReason = (typeof VIOLATION_REASONS)[number];

/** `GET /parking/spots/nearby` query params. Radius ≤ 50 000 m (default 1000), limit ≤ 50 (default 10). */
export interface NearbySearchParams {
  lat: number;
  lng: number;
  radius?: number;
  limit?: number;
}

/** Privacy-safe spot view for non-owner viewers — mirrors `PublicSpotResponse`. */
export interface PublicSpot {
  id: string;
  mediaId: string;
  latitude: number;
  longitude: number;
  addressText: string | null;
  description: string | null;
  manualLocationEdited: boolean;
  suitableVehicleTypes: SpotVehicleType[];
  parkingContext: ParkingContext;
  legalStatus: LegalStatus;
  violationReasons: ViolationReason[];
  status: ParkingStatus;
  /**
   * End of the advertised visibility window. Null while the spot is still pending
   * moderation (lifetime has not started). Only meaningful once published.
   */
  expiresAt: string | null;
  createdAt: string;
  updatedAt: string;
}

/** Owner's full spot view (`/parking/my-spots` endpoints) — mirrors `SpotResponse`. */
export interface Spot extends PublicSpot {
  ownerUserId: string;
  confidenceScore: number;
  verificationCount: number;
  filledReportCount: number;
}

/** Backend DTO name aliases. */
export type PublicSpotResponse = PublicSpot;
export type SpotResponse = Spot;
export type SpotMediaAccessUrlResponse = SpotMediaAccessUrl;

/**
 * Create-spot request — mirrors parking-service `CreateSpotRequest`.
 * Note: the backend rejects creation with `legalStatus: 'ILLEGAL_OR_RISKY'`
 * (422 ILLEGAL_SPOT_REJECTED).
 */
export interface CreateSpotRequest {
  mediaId: string;
  latitude: number;
  longitude: number;
  addressText?: string | null;
  description?: string | null;
  manualLocationEdited?: boolean;
  suitableVehicleTypes: SpotVehicleType[];
  parkingContext: ParkingContext;
  legalStatus: LegalStatus;
  violationReasons?: ViolationReason[] | null;
}

/** Backend DTO name alias. */
export type CreateParkingSpotRequest = CreateSpotRequest;

/** Outcome a verifying user reports — mirrors parking-service `VerificationResult`. */
export const VERIFICATION_RESULTS = [
  'AVAILABLE',
  'FILLED',
  'INVALID',
  'ILLEGAL_OR_RISKY',
  'WRONG_VEHICLE_SIZE',
] as const;

export type VerificationResult = (typeof VERIFICATION_RESULTS)[number];

/** Verify/report request — mirrors `VerifySpotRequest` (only the observed result). */
export interface VerifySpotRequest {
  result: VerificationResult;
}

/**
 * Short-lived signed URL for a spot photo.
 * Fetch on demand — URLs expire (default ~5m); do not cache long.
 */
export interface SpotMediaAccessUrl {
  spotId: string;
  mediaId: string;
  accessUrl: string;
  expiresAt: string;
}

/** Parking-session lifecycle values returned by parking-service. */
export const PARKING_SESSION_STATUSES = ['ACTIVE', 'COMPLETED', 'CANCELLED'] as const;

export type ParkingSessionStatus = (typeof PARKING_SESSION_STATUSES)[number];

/**
 * How a COMPLETED (or CANCELLED) parking session was closed.
 * ACTIVE sessions always have {@code null}. CANCELLED is always MANUAL.
 */
export const PARKING_SESSION_COMPLETION_TYPES = ['MANUAL', 'AUTO'] as const;

export type ParkingSessionCompletionType = (typeof PARKING_SESSION_COMPLETION_TYPES)[number];

/** Server-controlled origin of a parking session. */
export const PARKING_SOURCES = ['MANUAL', 'FACILITY', 'CURB', 'COMMUNITY', 'AUTO'] as const;

export type ParkingSource = (typeof PARKING_SOURCES)[number];

/** Exact decimal-string representation accepted by `POST /parking/sessions`. */
export type ParkingMoney = string;

/** Manual session start body. Session source and user identity are never client inputs. */
export interface StartParkingSessionRequest {
  latitude: number;
  longitude: number;
  estimatedFee?: ParkingMoney | null;
}

/** Public parking-session representation returned by parking-service. */
export interface ParkingSessionResponse {
  id: string;
  status: ParkingSessionStatus;
  parkingSource: ParkingSource;
  startedAt: string;
  endedAt: string | null;
  latitude: number;
  longitude: number;
  estimatedFee: ParkingMoney | null;
  /** Heartbeat for stale-session warnings; equals startedAt for a fresh ACTIVE session. */
  lastConfirmedAt: string | null;
  /** Null while ACTIVE; MANUAL or AUTO once COMPLETED; MANUAL when CANCELLED. */
  completionType: ParkingSessionCompletionType | null;
}

/** Opaque-cursor history query. */
export interface ParkingSessionHistoryParams {
  size?: number;
  cursor?: string;
}

/** Bounded terminal-session page returned by `GET /parking/sessions/history`. */
export interface ParkingSessionHistoryResponse {
  items: ParkingSessionResponse[];
  nextCursor: string | null;
}

/** The canonical community-claim endpoint preserves `PublicSpotResponse`. */
export type CommunityClaimResponse = PublicSpotResponse;
