import type { AxiosInstance } from 'axios';
import type {
  Profile,
  PublicProfile,
  SmartReturnSettings,
  SmartReturnTodayRequest,
  UpdatePreferenceRequest,
  UpdateProfileRequest,
  UpdateSmartReturnSettingsRequest,
  UpsertVehicleRequest,
  UserPreference,
  UserStats,
  VehicleProfile,
} from '@parkio/types';
import type { RequestOptions } from './request-options';

export function createUsersApi(client: AxiosInstance) {
  return {
    getMyProfile(options?: RequestOptions): Promise<Profile> {
      return client.get<Profile>('/users/me', { signal: options?.signal }).then((r) => r.data);
    },

    updateMyProfile(body: UpdateProfileRequest): Promise<Profile> {
      return client.patch<Profile>('/users/me', body).then((r) => r.data);
    },

    getMyPreferences(options?: RequestOptions): Promise<UserPreference> {
      return client
        .get<UserPreference>('/users/me/preferences', { signal: options?.signal })
        .then((r) => r.data);
    },

    updateMyPreferences(body: UpdatePreferenceRequest): Promise<UserPreference> {
      return client.patch<UserPreference>('/users/me/preferences', body).then((r) => r.data);
    },

    getSmartReturn(options?: RequestOptions): Promise<SmartReturnSettings> {
      return client
        .get<SmartReturnSettings>('/users/me/smart-return', { signal: options?.signal })
        .then((r) => r.data);
    },

    updateSmartReturnSettings(body: UpdateSmartReturnSettingsRequest): Promise<SmartReturnSettings> {
      return client.put<SmartReturnSettings>('/users/me/smart-return/settings', body).then((r) => r.data);
    },

    smartReturnLeftByCar(body: SmartReturnTodayRequest): Promise<SmartReturnSettings> {
      return client.post<SmartReturnSettings>('/users/me/smart-return/today/left-by-car', body).then((r) => r.data);
    },

    smartReturnNotByCar(): Promise<SmartReturnSettings> {
      return client.post<SmartReturnSettings>('/users/me/smart-return/today/not-by-car').then((r) => r.data);
    },

    updateSmartReturnTime(body: SmartReturnTodayRequest): Promise<SmartReturnSettings> {
      return client.put<SmartReturnSettings>('/users/me/smart-return/today/return-time', body).then((r) => r.data);
    },

    cancelSmartReturnToday(): Promise<SmartReturnSettings> {
      return client.post<SmartReturnSettings>('/users/me/smart-return/today/cancel').then((r) => r.data);
    },

    getMyVehicle(options?: RequestOptions): Promise<VehicleProfile> {
      return client
        .get<VehicleProfile>('/users/me/vehicle', { signal: options?.signal })
        .then((r) => r.data);
    },

    upsertMyVehicle(body: UpsertVehicleRequest): Promise<VehicleProfile> {
      return client.put<VehicleProfile>('/users/me/vehicle', body).then((r) => r.data);
    },

    getMyStats(options?: RequestOptions): Promise<UserStats> {
      return client.get<UserStats>('/users/me/stats', { signal: options?.signal }).then((r) => r.data);
    },

    getPublicProfile(userId: string, options?: RequestOptions): Promise<PublicProfile> {
      return client
        .get<PublicProfile>(`/users/${userId}/public-profile`, { signal: options?.signal })
        .then((r) => r.data);
    },
  };
}

export type UsersApi = ReturnType<typeof createUsersApi>;
