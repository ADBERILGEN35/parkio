import { useCallback, useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { createIdempotencyKey, isParkioApiError } from '@parkio/api-client';
import type { CreateSpotRequest, Spot } from '@parkio/types';
import { parkingApi } from '@/services/api';
import { useShareDraftStore } from './state/shareDraftStore';
import { deleteDraftPhoto } from './prepareImage';

/** MEDIA_NOT_READY retry policy: the ClamAV scan almost always wins this race. */
const MEDIA_RETRY_ATTEMPTS = 4;
const MEDIA_RETRY_DELAY_MS = 2500;

export type PublishPhase = 'idle' | 'publishing' | 'waitingMedia';

/**
 * Builds the CreateSpotRequest from the draft and publishes it. One
 * idempotency key per publish session (retries are dedupe-safe), a bounded
 * retry loop absorbs the media-scan race, and on success the draft is cleared
 * while the created spot is handed back for the success screen.
 */
export function useCreateSpot() {
  const queryClient = useQueryClient();
  const [phase, setPhase] = useState<PublishPhase>('idle');
  const keyRef = useRef<string | null>(null);

  const publish = useCallback(async (): Promise<Spot> => {
    const draft = useShareDraftStore.getState();
    if (!draft.mediaId || !draft.location || !draft.legalStatus) {
      throw new Error('draft-incomplete');
    }
    const idempotencyKey = (keyRef.current ??= createIdempotencyKey());

    const address = draft.addressText.trim();
    const description = draft.description.trim();
    const request: CreateSpotRequest = {
      mediaId: draft.mediaId,
      latitude: draft.location.latitude,
      longitude: draft.location.longitude,
      ...(address ? { addressText: address.slice(0, 512) } : {}),
      ...(description ? { description: description.slice(0, 1000) } : {}),
      manualLocationEdited: draft.manualLocationEdited,
      suitableVehicleTypes: draft.vehicleTypes.length > 0 ? draft.vehicleTypes : ['ANY'],
      parkingContext: draft.parkingContext,
      legalStatus: draft.legalStatus,
      ...(draft.violationReasons.length > 0 ? { violationReasons: draft.violationReasons } : {}),
    };

    setPhase('publishing');
    try {
      let attempt = 0;
      // Bounded loop: only MEDIA_NOT_READY re-enters.
      for (;;) {
        try {
          const spot = await parkingApi.createParkingSpot(request, idempotencyKey);
          keyRef.current = null;
          // Draft is spent — clear storage and the persisted photo copy.
          useShareDraftStore.getState().reset();
          deleteDraftPhoto();
          void queryClient.invalidateQueries({ queryKey: ['my-spots'] });
          void queryClient.invalidateQueries({ queryKey: ['nearby'] });
          return spot;
        } catch (error) {
          const mediaRace =
            isParkioApiError(error) && error.code === 'MEDIA_NOT_READY' && attempt < MEDIA_RETRY_ATTEMPTS;
          if (!mediaRace) {
            throw error;
          }
          attempt += 1;
          setPhase('waitingMedia');
          await new Promise((resolve) => setTimeout(resolve, MEDIA_RETRY_DELAY_MS));
          setPhase('publishing');
        }
      }
    } finally {
      setPhase('idle');
    }
  }, [queryClient]);

  return { publish, phase };
}
