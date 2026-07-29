import type { SpotRejection } from '@parkio/types';
import {
  ModerationDecisionCard,
  type DecisionTechnicalEvidence,
} from '@/components/moderation/ModerationDecisionCard';

/**
 * @deprecated Prefer {@link ModerationDecisionCard}. Thin compatibility wrapper
 * used by existing spot/moderation call sites.
 */
export function SpotRejectionPanel({
  rejection,
  status,
  confidenceScore,
  technical,
}: {
  rejection: SpotRejection | null | undefined;
  status?: string | null;
  confidenceScore?: number | null;
  technical?: DecisionTechnicalEvidence | null;
}) {
  return (
    <ModerationDecisionCard
      rejection={rejection}
      status={status}
      confidenceScore={confidenceScore}
      technical={technical}
    />
  );
}
