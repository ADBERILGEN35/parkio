export interface ClaimedRegion {
  /** Normalized left edge in [0, 1]. */
  x: number;
  /** Normalized top edge in [0, 1]. */
  y: number;
  /** Normalized width in (0, 1]. */
  width: number;
  /** Normalized height in (0, 1]. */
  height: number;
}

/** Minimum fraction of image area required for a usable annotation. */
export const CLAIMED_REGION_MIN_AREA = 0.05;

export function isValidClaimedRegion(region: ClaimedRegion | null | undefined): boolean {
  if (!region) {
    return false;
  }
  const { x, y, width, height } = region;
  if (
    !Number.isFinite(x) ||
    !Number.isFinite(y) ||
    !Number.isFinite(width) ||
    !Number.isFinite(height)
  ) {
    return false;
  }
  if (x < 0 || y < 0 || width <= 0 || height <= 0) {
    return false;
  }
  if (x + width > 1.0000001 || y + height > 1.0000001) {
    return false;
  }
  return width * height >= CLAIMED_REGION_MIN_AREA;
}

export interface UploadMediaResponse {
  mediaId: string;
  status: string;
  contentType: string;
  fileSize: number;
  claimedRegion?: ClaimedRegion | null;
}

export interface MediaAccessUrl {
  mediaId: string;
  accessUrl: string;
  expiresAt: string;
}

export interface MediaMetadata {
  mediaId: string;
  ownerUserId: string;
  contentType: string;
  fileSize: number;
  status: string;
  claimedRegion?: ClaimedRegion | null;
  createdAt: string;
  updatedAt: string;
}

/** Product decision derived from AI PASSED/WARNING/FAILED. */
export type AiValidationDecision = 'ACCEPT' | 'REVIEW' | 'REJECT';

export type AiValidationStatus = 'PASSED' | 'WARNING' | 'FAILED';

export interface AiValidationResult {
  id: string;
  mediaId: string;
  parkingSpotId?: string | null;
  requestedByUserId?: string | null;
  status: AiValidationStatus;
  decision: AiValidationDecision;
  reasonCode?: string | null;
  claimedRegionAssessment?: string | null;
  vehicleFitEstimate?: string | null;
  obstructionAssessment?: string | null;
  legalityAccessAssessment?: string | null;
  emptySpaceConfidence: number;
  legalRiskScore: number;
  imageQualityScore: number;
  aiConfidence: number;
  detectedRiskTypes: string[];
  createdAt: string;
}
