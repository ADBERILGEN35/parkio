import type { AxiosInstance } from 'axios';
import type {
  Profile,
  PublicProfile,
  UpdatePreferenceRequest,
  UpdateProfileRequest,
  UpsertVehicleRequest,
  UserPreference,
  UserStats,
  VehicleProfile,
} from '@parkio/types';

export function createUsersApi(client: AxiosInstance) {
  return {
    getMyProfile(): Promise<Profile> {
      return client.get<Profile>('/users/me').then((r) => r.data);
    },

    updateMyProfile(body: UpdateProfileRequest): Promise<Profile> {
      return client.patch<Profile>('/users/me', body).then((r) => r.data);
    },

    getMyPreferences(): Promise<UserPreference> {
      return client.get<UserPreference>('/users/me/preferences').then((r) => r.data);
    },

    updateMyPreferences(body: UpdatePreferenceRequest): Promise<UserPreference> {
      return client.patch<UserPreference>('/users/me/preferences', body).then((r) => r.data);
    },

    getMyVehicle(): Promise<VehicleProfile> {
      return client.get<VehicleProfile>('/users/me/vehicle').then((r) => r.data);
    },

    upsertMyVehicle(body: UpsertVehicleRequest): Promise<VehicleProfile> {
      return client.put<VehicleProfile>('/users/me/vehicle', body).then((r) => r.data);
    },

    getMyStats(): Promise<UserStats> {
      return client.get<UserStats>('/users/me/stats').then((r) => r.data);
    },

    getPublicProfile(userId: string): Promise<PublicProfile> {
      return client.get<PublicProfile>(`/users/${userId}/public-profile`).then((r) => r.data);
    },
  };
}

export type UsersApi = ReturnType<typeof createUsersApi>;
