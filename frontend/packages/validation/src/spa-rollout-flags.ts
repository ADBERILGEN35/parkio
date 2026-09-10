/**
 * Sanitized SPA rollout flag snapshot for ops diagnostics (WP-SPA-12).
 * Booleans / bounded numbers only — never secrets or raw env strings.
 */

export type SpaRolloutFlagSnapshot = {
  platform: 'web' | 'mobile_v2';
  smartParkingAssistantEnabled: boolean;
};

export function spaRolloutFlagSnapshotWeb(enabled: boolean): SpaRolloutFlagSnapshot {
  return {
    platform: 'web',
    smartParkingAssistantEnabled: enabled === true,
  };
}

export function spaRolloutFlagSnapshotMobile(enabled: boolean): SpaRolloutFlagSnapshot {
  return {
    platform: 'mobile_v2',
    smartParkingAssistantEnabled: enabled === true,
  };
}
