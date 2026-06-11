/** Lifecycle of a parking spot — mirrors parking-service `ParkingSpotStatus`. */
export const PARKING_STATUSES = [
  'ACTIVE',
  'VERIFIED',
  'SUSPICIOUS',
  'FILLED',
  'EXPIRED',
  'REJECTED',
] as const;

export type ParkingStatus = (typeof PARKING_STATUSES)[number];

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
  violationReasons: string[];
  status: ParkingStatus;
  expiresAt: string;
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
  addressText?: string;
  description?: string;
  manualLocationEdited?: boolean;
  suitableVehicleTypes: SpotVehicleType[];
  parkingContext: ParkingContext;
  legalStatus: LegalStatus;
  violationReasons?: ViolationReason[];
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
