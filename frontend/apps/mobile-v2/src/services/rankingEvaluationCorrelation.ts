/**
 * Privacy-safe ranking evaluation correlation (WP-SPA-14B) for mobile-v2.
 * Module-scoped context only — no React snapshots / getSnapshot loops.
 */

import type { ParkingApi } from '@parkio/api-client';
import type {
  RankingEvaluationOutcomeType,
  RankingEvaluationPlatform,
  RecommendationResponse,
} from '@parkio/types';

type RankingEvaluationParkingApi = Pick<ParkingApi, 'recordRankingEvaluationOutcome'>;

const PLATFORM: RankingEvaluationPlatform = 'MOBILE_V2';

let parkingApi: RankingEvaluationParkingApi | null = null;
let evaluationId: string | null = null;
let candidateOrdinalById = new Map<string, number>();
let selectedOrdinal: number | null = null;
const recordedKeys = new Set<string>();

export function configureRankingEvaluationApi(api: RankingEvaluationParkingApi | null): void {
  parkingApi = api;
}

export function resetRankingEvaluationForTests(): void {
  parkingApi = null;
  evaluationId = null;
  candidateOrdinalById = new Map();
  selectedOrdinal = null;
  recordedKeys.clear();
}

export function bindRecommendationEvaluation(response: RecommendationResponse): void {
  recordedKeys.clear();
  selectedOrdinal = null;
  candidateOrdinalById = new Map();

  const id = response.evaluationId ?? null;
  if (!id) {
    evaluationId = null;
    return;
  }

  evaluationId = id;
  response.candidates.forEach((candidate, index) => {
    candidateOrdinalById.set(candidate.id, index);
  });
}

export function clearRankingEvaluation(): void {
  evaluationId = null;
  candidateOrdinalById = new Map();
  selectedOrdinal = null;
  recordedKeys.clear();
}

export function setSelectedCandidateOrdinal(ordinal: number | null): void {
  selectedOrdinal = ordinal;
}

export function setSelectedCandidateById(candidateId: string | null): void {
  if (candidateId == null) {
    selectedOrdinal = null;
    return;
  }
  selectedOrdinal = candidateOrdinalById.has(candidateId)
    ? (candidateOrdinalById.get(candidateId) as number)
    : null;
}

function resolveOrdinal(args: {
  candidateId?: string | null;
  candidateOrdinal?: number | null;
}): number | null {
  if (typeof args.candidateOrdinal === 'number' && Number.isFinite(args.candidateOrdinal)) {
    return args.candidateOrdinal;
  }
  if (args.candidateId != null && candidateOrdinalById.has(args.candidateId)) {
    return candidateOrdinalById.get(args.candidateId) as number;
  }
  return selectedOrdinal;
}

export function recordRankingOutcome(args: {
  outcomeType: RankingEvaluationOutcomeType;
  candidateId?: string | null;
  candidateOrdinal?: number | null;
  platform?: RankingEvaluationPlatform;
  latencyBucket?: string | null;
}): void {
  try {
    if (!evaluationId || !parkingApi) return;
    const ordinal = resolveOrdinal(args);
    if (ordinal == null || ordinal < 0) return;

    const key = `${evaluationId}:${ordinal}:${args.outcomeType}`;
    if (recordedKeys.has(key)) return;
    recordedKeys.add(key);

    void parkingApi
      .recordRankingEvaluationOutcome({
        evaluationId,
        candidateOrdinal: ordinal,
        outcomeType: args.outcomeType,
        platform: args.platform ?? PLATFORM,
        ...(args.latencyBucket != null ? { latencyBucket: args.latencyBucket } : {}),
      })
      .catch(() => {
        // fail-open — allow a later retry attempt if the request never landed
        recordedKeys.delete(key);
      });
  } catch {
    // fail-open
  }
}

/** Records an outcome for the currently selected candidate ordinal. */
export function recordOutcomeForSelectedCandidate(
  outcomeType: RankingEvaluationOutcomeType,
  platform: RankingEvaluationPlatform = PLATFORM,
): void {
  if (selectedOrdinal == null) return;
  recordRankingOutcome({ outcomeType, candidateOrdinal: selectedOrdinal, platform });
}
