/** Effective parking-session stale lifecycle config from parking-service. */
export interface ParkingSessionLifecycleConfig {
  confirmAfterMs: number;
  reminder2AfterMs: number;
  autoCompleteAfterMs: number;
  confirmAfter: string;
  reminder2After: string;
  autoCompleteAfter: string;
  remindersEnabled: boolean;
  autoCompleteEnabled: boolean;
}