import type {
  ParkedCarTargetRef,
  ParkedCarView,
  ParkingSessionResponse,
  RecentParkingTargetKind,
  RecordRecentParkingRequest,
} from '@parkio/types';

/** Usable WGS84 coordinate for return-to-car / marker. */
export function isUsableParkedCarCoordinate(
  latitude: unknown,
  longitude: unknown,
): boolean {
  return (
    typeof latitude === 'number' &&
    typeof longitude === 'number' &&
    Number.isFinite(latitude) &&
    Number.isFinite(longitude) &&
    latitude >= -90 &&
    latitude <= 90 &&
    longitude >= -180 &&
    longitude <= 180
  );
}

/**
 * Build a ParkedCarView from an ACTIVE ParkingSession.
 * Returns null when there is no usable active session.
 */
export function toParkedCarView(
  session: ParkingSessionResponse | null | undefined,
  options?: {
    nowMs?: number;
    target?: ParkedCarTargetRef | null;
  },
): ParkedCarView | null {
  if (!session || session.status !== 'ACTIVE') return null;
  if (!isUsableParkedCarCoordinate(session.latitude, session.longitude)) return null;

  const parkedAtMs = Date.parse(session.startedAt);
  const nowMs = options?.nowMs ?? Date.now();
  let elapsedMinutes: number | null = null;
  if (Number.isFinite(parkedAtMs) && parkedAtMs <= nowMs) {
    elapsedMinutes = Math.max(0, Math.floor((nowMs - parkedAtMs) / 60_000));
  }

  const target = options?.target ?? null;
  const displayLabel = target?.displayLabel?.trim() || null;

  return {
    sessionId: session.id,
    status: 'ACTIVE',
    lifecycle: 'ACTIVE',
    parkedAt: session.startedAt,
    latitude: session.latitude,
    longitude: session.longitude,
    parkingSource: session.parkingSource,
    returnAvailable: true,
    elapsedMinutes,
    target,
    displayLabel,
  };
}

/** True when a successful park should also record RecentParking. */
export function shouldRecordRecentParking(
  target: ParkedCarTargetRef | null | undefined,
): target is ParkedCarTargetRef {
  if (!target) return false;
  if (target.kind !== 'MUNICIPAL_FACILITY') return false;
  const id = target.targetId?.trim();
  return Boolean(id);
}

export function toRecordRecentParkingRequest(
  target: ParkedCarTargetRef,
): RecordRecentParkingRequest {
  return {
    targetKind: target.kind as RecentParkingTargetKind,
    targetId: target.targetId.trim(),
  };
}

/**
 * Build a municipal Park Here target from a facility or recommendation ref.
 */
export function municipalParkTarget(
  facilityId: string,
  displayLabel?: string | null,
): ParkedCarTargetRef {
  return {
    kind: 'MUNICIPAL_FACILITY',
    targetId: facilityId.trim(),
    displayLabel: displayLabel?.trim() || null,
  };
}
