import type {
  AiValidationResult,
  ApiError,
  AuthResponse,
  CommunityClaimResponse,
  MediaAccessUrl,
  MediaMetadata,
  ParkingSessionHistoryResponse,
  ParkingSessionResponse,
  StartParkingSessionRequest,
} from '@parkio/types';

/**
 * Frozen Sprint 2.3 wire fixtures. Changes require a backend-contract comparison;
 * tests consume these values without involving transport or application runtime.
 */
export const startParkingSessionWithoutFeeFixture = {
  latitude: 41.0082,
  longitude: 28.9784,
} satisfies StartParkingSessionRequest;

export const startParkingSessionWithFeeFixture = {
  latitude: 41.0082,
  longitude: 28.9784,
  estimatedFee: '125.50',
} satisfies StartParkingSessionRequest;

export const activeParkingSessionFixture = {
  id: 'd431ad5a-f8ce-4be2-b4dc-248b47990b39',
  status: 'ACTIVE',
  parkingSource: 'MANUAL',
  startedAt: '2026-07-21T09:00:00Z',
  endedAt: null,
  latitude: 41.0082,
  longitude: 28.9784,
  estimatedFee: '125.50',
  lastConfirmedAt: '2026-07-21T09:00:00Z',
  completionType: null,
} satisfies ParkingSessionResponse;

export const completedParkingSessionFixture = {
  ...activeParkingSessionFixture,
  status: 'COMPLETED',
  endedAt: '2026-07-21T11:15:00Z',
  completionType: 'MANUAL',
} satisfies ParkingSessionResponse;

export const cancelledParkingSessionFixture = {
  ...activeParkingSessionFixture,
  status: 'CANCELLED',
  endedAt: '2026-07-21T09:05:00Z',
  estimatedFee: null,
  completionType: 'MANUAL',
} satisfies ParkingSessionResponse;

export const communityParkingSessionFixture = {
  ...activeParkingSessionFixture,
  parkingSource: 'COMMUNITY',
  estimatedFee: null,
} satisfies ParkingSessionResponse;

export const parkingSessionHistoryFixture = {
  items: [completedParkingSessionFixture],
  nextCursor:
    'eyJ2IjoxLCJzdGFydGVkQXQiOiIyMDI2LTA3LTIxVDA5OjAwOjAwWiIsImlkIjoiZDQzMWFkNWEtZjhjZS00YmUyLWI0ZGMtMjQ4YjQ3OTkwYjM5In0',
} satisfies ParkingSessionHistoryResponse;

export const communityClaimFixture = {
  id: '2b371445-8ab4-4a23-a1bd-9eb084187cf7',
  mediaId: '81518eb3-a6d8-453f-aeb9-bdf9dc73457d',
  latitude: 41.0082,
  longitude: 28.9784,
  addressText: 'Alemdar, Istanbul',
  description: null,
  manualLocationEdited: false,
  suitableVehicleTypes: ['SEDAN'],
  parkingContext: 'STREET_PARKING',
  legalStatus: 'LEGAL',
  violationReasons: [],
  status: 'FILLED',
  expiresAt: '2026-07-22T12:10:00Z',
  createdAt: '2026-07-22T12:00:00Z',
  updatedAt: '2026-07-22T12:04:00Z',
} satisfies CommunityClaimResponse;

export const apiErrorFixture = {
  code: 'VALIDATION_FAILED',
  message: 'Request validation failed.',
  traceId: '8a56ef7e-69de-4f3c-8fe5-32b83d67f1b4',
  fieldErrors: [
    {
      field: 'estimatedFee',
      message: 'estimatedFee must be a non-negative decimal string',
    },
  ],
  timestamp: '2026-07-21T09:00:00Z',
} satisfies ApiError;

export const webAuthResponseFixture = {
  accessToken: 'access-token',
  tokenType: 'Bearer',
  accessTokenExpiresAt: '2026-07-22T12:15:00Z',
  refreshTokenExpiresAt: '2026-08-21T12:00:00Z',
  user: {
    id: '8a56ef7e-69de-4f3c-8fe5-32b83d67f1b4',
    email: 'driver@parkio.dev',
    status: 'ACTIVE',
    roles: ['USER'],
  },
} satisfies AuthResponse;

export const nativeAuthResponseFixture = {
  ...webAuthResponseFixture,
  refreshToken: 'native-refresh-token',
} satisfies AuthResponse;

export const pendingVerificationAuthResponseFixture = {
  accessToken: null,
  tokenType: 'Bearer',
  accessTokenExpiresAt: null,
  refreshTokenExpiresAt: null,
  user: {
    id: '6ad6578a-7af0-4c24-923b-c2fb763237ee',
    email: 'pending@parkio.dev',
    status: 'PENDING_VERIFICATION',
    roles: ['USER'],
  },
} satisfies AuthResponse;

export const mediaAccessUrlFixture = {
  mediaId: '81518eb3-a6d8-453f-aeb9-bdf9dc73457d',
  accessUrl: 'https://media.parkio.dev/access/81518eb3-a6d8-453f-aeb9-bdf9dc73457d',
  expiresAt: '2026-07-22T12:05:00Z',
} satisfies MediaAccessUrl;

export const mediaMetadataFixture = {
  mediaId: '81518eb3-a6d8-453f-aeb9-bdf9dc73457d',
  ownerUserId: '8a56ef7e-69de-4f3c-8fe5-32b83d67f1b4',
  contentType: 'image/jpeg',
  fileSize: 1_024,
  status: 'READY',
  claimedRegion: { x: 0.1, y: 0.1, width: 0.5, height: 0.5 },
  createdAt: '2026-07-22T12:00:00Z',
  updatedAt: '2026-07-22T12:00:01Z',
} satisfies MediaMetadata;

export const aiValidationResultFixture = {
  id: '6ad6578a-7af0-4c24-923b-c2fb763237ee',
  mediaId: '81518eb3-a6d8-453f-aeb9-bdf9dc73457d',
  parkingSpotId: '2b371445-8ab4-4a23-a1bd-9eb084187cf7',
  requestedByUserId: null,
  status: 'WARNING',
  decision: 'REVIEW',
  reasonCode: 'LEGAL_RISK_REVIEW',
  claimedRegionAssessment: 'VALID',
  vehicleFitEstimate: 'SEDAN',
  obstructionAssessment: null,
  legalityAccessAssessment: 'REVIEW',
  emptySpaceConfidence: 82,
  legalRiskScore: 40,
  imageQualityScore: 91,
  aiConfidence: 86,
  detectedRiskTypes: ['NO_PARKING_SIGN'],
  findings: [
    {
      id: '90cfd91e-1069-4677-b484-876a60f9881d',
      validationType: 'LEGAL_RISK_DETECTION',
      riskType: 'NO_PARKING_SIGN',
      score: 40,
      message: 'Advisory legal-risk signal detected.',
      createdAt: '2026-07-22T12:00:02Z',
    },
  ],
  vehicleFitEstimates: [
    {
      id: 'e34637aa-d4ec-46dd-b348-55085bc483d4',
      vehicleType: 'SEDAN',
      fitScore: 82,
      createdAt: '2026-07-22T12:00:02Z',
    },
  ],
  createdAt: '2026-07-22T12:00:02Z',
} satisfies AiValidationResult;
