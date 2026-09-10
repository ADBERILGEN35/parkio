import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ParkingCandidate, RecommendationResponse } from '@parkio/types';
import {
  bindRecommendationEvaluation,
  clearRankingEvaluation,
  configureRankingEvaluationApi,
  recordOutcomeForSelectedCandidate,
  recordRankingOutcome,
  resetRankingEvaluationForTests,
  setSelectedCandidateById,
} from './rankingEvaluationCorrelation';

const EVAL_ID = '11111111-1111-4111-8111-111111111111';

function candidate(id: string): ParkingCandidate {
  return {
    id,
    channel: 'MUNICIPAL_FACILITY',
    refId: 'cccccccc-cccc-cccc-cccc-cccccccccccc',
    title: 'A',
    latitude: 1,
    longitude: 2,
    distanceMeters: 10,
    baselineOrder: 0,
    reasons: [],
  };
}

function response(overrides?: Partial<RecommendationResponse>): RecommendationResponse {
  return {
    destination: {
      label: 'x',
      latitude: 1,
      longitude: 2,
      source: 'GEOCODING',
    },
    generatedAt: '2026-01-01T00:00:00Z',
    partial: false,
    inventoryStatus: { community: 'EMPTY', municipal: 'AVAILABLE' },
    candidates: [candidate('c0'), candidate('c1')],
    evaluationId: EVAL_ID,
    ...overrides,
  };
}

describe('rankingEvaluationCorrelation (web)', () => {
  const recordRankingEvaluationOutcome = vi.fn().mockResolvedValue({ status: 'RECORDED' });

  beforeEach(() => {
    resetRankingEvaluationForTests();
    recordRankingEvaluationOutcome.mockClear();
    recordRankingEvaluationOutcome.mockResolvedValue({ status: 'RECORDED' });
    configureRankingEvaluationApi({ recordRankingEvaluationOutcome });
  });

  afterEach(() => {
    resetRankingEvaluationForTests();
  });

  it('binds evaluationId and records selected ordinal', async () => {
    bindRecommendationEvaluation(response());
    setSelectedCandidateById('c1');
    recordRankingOutcome({
      outcomeType: 'RECOMMENDATION_SELECTED',
      candidateId: 'c1',
      platform: 'WEB',
    });

    expect(recordRankingEvaluationOutcome).toHaveBeenCalledTimes(1);
    expect(recordRankingEvaluationOutcome).toHaveBeenCalledWith({
      evaluationId: EVAL_ID,
      candidateOrdinal: 1,
      outcomeType: 'RECOMMENDATION_SELECTED',
      platform: 'WEB',
    });
  });

  it('dedupes identical evaluationId+ordinal+outcomeType', () => {
    bindRecommendationEvaluation(response());
    setSelectedCandidateById('c0');
    recordOutcomeForSelectedCandidate('NAVIGATION_STARTED');
    recordOutcomeForSelectedCandidate('NAVIGATION_STARTED');
    expect(recordRankingEvaluationOutcome).toHaveBeenCalledTimes(1);
  });

  it('clears context so later outcomes are no-ops', () => {
    bindRecommendationEvaluation(response());
    setSelectedCandidateById('c0');
    clearRankingEvaluation();
    recordOutcomeForSelectedCandidate('PARKING_SESSION_STARTED');
    expect(recordRankingEvaluationOutcome).not.toHaveBeenCalled();
  });

  it('fails open when the API rejects', async () => {
    recordRankingEvaluationOutcome.mockRejectedValueOnce(new Error('network'));
    bindRecommendationEvaluation(response());
    expect(() =>
      recordRankingOutcome({
        outcomeType: 'RECOMMENDATION_SELECTED',
        candidateOrdinal: 0,
        platform: 'WEB',
      }),
    ).not.toThrow();
    await vi.waitFor(() => {
      expect(recordRankingEvaluationOutcome).toHaveBeenCalledTimes(1);
    });
  });

  it('no-ops when evaluationId is absent', () => {
    bindRecommendationEvaluation(response({ evaluationId: null }));
    recordRankingOutcome({
      outcomeType: 'RECOMMENDATION_SELECTED',
      candidateOrdinal: 0,
      platform: 'WEB',
    });
    expect(recordRankingEvaluationOutcome).not.toHaveBeenCalled();
  });
});
