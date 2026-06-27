/** Vehicle sizes a user may drive — mirrors user-service `VehicleType`. */
export const VEHICLE_TYPES = [
  'MOTORCYCLE',
  'SMALL_CAR',
  'SEDAN',
  'SUV',
  'VAN',
  'TRUCK',
] as const;

export type VehicleType = (typeof VEHICLE_TYPES)[number];

/** The caller's own profile (`GET /users/me`) — mirrors `ProfileResponse`. */
export interface Profile {
  id: string;
  authUserId: string;
  email: string;
  displayName: string | null;
  phoneNumber: string | null;
  city: string | null;
  status: string;
  createdAt: string;
}

/** Partial profile update (`PATCH /users/me`). Omitted fields are left unchanged. */
export interface UpdateProfileRequest {
  displayName?: string;
  phoneNumber?: string;
  city?: string;
}

/** `GET /users/me/preferences` — mirrors `PreferencesResponse`. */
export interface UserPreference {
  preferredRadiusMeters: number;
  notificationsEnabled: boolean;
}

/** Partial preferences update (`PATCH /users/me/preferences`). Omitted fields are left unchanged. */
export interface UpdatePreferenceRequest {
  preferredRadiusMeters?: number;
  notificationsEnabled?: boolean;
}

export type SmartReturnTodayStatus =
  | 'UNKNOWN'
  | 'LEFT_BY_CAR'
  | 'RETURN_CHECK_IN_PROGRESS'
  | 'NOT_BY_CAR'
  | 'CANCELLED';

export interface SmartReturnSettings {
  enabled: boolean;
  homeLatitude: number | null;
  homeLongitude: number | null;
  homeLabel: string | null;
  defaultReturnTime: string | null;
  reminderLeadMinutes: number;
  lastPromptDate: string | null;
  todayStatus: SmartReturnTodayStatus;
  todayExpectedReturnAt: string | null;
  todayReturnCheckCompletedAt: string | null;
  todayNotificationSentAt: string | null;
}

export interface UpdateSmartReturnSettingsRequest {
  enabled?: boolean;
  homeLatitude?: number | null;
  homeLongitude?: number | null;
  homeLabel?: string | null;
  defaultReturnTime?: string | null;
  reminderLeadMinutes?: number;
}

export interface SmartReturnTodayRequest {
  expectedReturnAt: string;
}

/** `GET /users/me/vehicle` — mirrors `VehicleResponse`. Null fields mean no vehicle is set. */
export interface VehicleProfile {
  vehicleType: VehicleType | null;
  plate: string | null;
}

/** Full vehicle replacement (`PUT /users/me/vehicle`). Null/omitted fields clear the value. */
export interface UpsertVehicleRequest {
  vehicleType?: VehicleType | null;
  plate?: string | null;
}

/** `GET /users/me/stats` — mirrors `StatsResponse` (trust + gamification projection). */
export interface UserStats {
  trustScore: number;
  trustBand: string;
  totalPoints: number;
  currentLevel: number;
}

/**
 * Public profile of another user (`GET /users/{userId}/public-profile`).
 * Privacy-safe: no email, phone or plate. `userId` is the platform-wide auth user id.
 */
export interface PublicProfile {
  userId: string;
  displayName: string | null;
  city: string | null;
  trustBand: string;
  currentLevel: number;
  status: string;
  memberSince: string;
}
