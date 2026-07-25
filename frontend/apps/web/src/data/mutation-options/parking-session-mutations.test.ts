import type { ParkingSessionResponse } from '@parkio/types';
import { describe, expect, it, vi } from 'vitest';
import type { ParkioSdk } from '@/app/sdk';
import { parkingKeys } from '@/data/keys';
import { createTestQueryClient } from '@/test/utils';
import {
  createCancelParkingSessionMutationOptions,
  createCompleteParkingSessionMutationOptions,
  createDeleteParkingSessionHistoryMutationOptions,
  createDeleteParkingSessionMutationOptions,
  createStartParkingSessionMutationOptions,
} from './parkingSession';

const sessionId = '11111111-1111-4111-8111-111111111111';

const activeSession: ParkingSessionResponse = {
  id: sessionId,
  status: 'ACTIVE',
  parkingSource: 'MANUAL',
  startedAt: '2026-07-25T10:00:00.000Z',
  endedAt: null,
  latitude: 41,
  longitude: 29,
  estimatedFee: null,
};

const completedSession: ParkingSessionResponse = {
  ...activeSession,
  status: 'COMPLETED',
  endedAt: '2026-07-25T11:00:00.000Z',
};

function createSdk(): ParkioSdk {
  return {
    parkingApi: {
      startParkingSession: vi.fn(async () => activeSession),
      completeParkingSession: vi.fn(async () => completedSession),
      cancelParkingSession: vi.fn(async () => ({ ...completedSession, status: 'CANCELLED' })),
      deleteParkingSession: vi.fn(async () => undefined),
      deleteParkingSessionHistory: vi.fn(async () => undefined),
    },
  } as unknown as ParkioSdk;
}

describe('ParkingSession mutation options', () => {
  it('start writes the returned session into the active-session cache', async () => {
    const sdk = createSdk();
    const client = createTestQueryClient();
    const options = createStartParkingSessionMutationOptions(sdk, client);

    const started = await options.mutationFn({ latitude: 41, longitude: 29 });
    options.onSuccess(started);

    expect(sdk.parkingApi.startParkingSession).toHaveBeenCalledOnce();
    expect(client.getQueryData(parkingKeys.activeSession())).toEqual(activeSession);
  });

  it('complete clears the active-session cache and refreshes history', async () => {
    const sdk = createSdk();
    const client = createTestQueryClient();
    client.setQueryData(parkingKeys.activeSession(), activeSession);
    const invalidate = vi.spyOn(client, 'invalidateQueries');
    const options = createCompleteParkingSessionMutationOptions(sdk, client);

    const result = await options.mutationFn(sessionId);
    await options.onSuccess(result);

    expect(sdk.parkingApi.completeParkingSession).toHaveBeenCalledOnce();
    expect(client.getQueryData(parkingKeys.activeSession())).toBeNull();
    expect(invalidate).toHaveBeenCalledWith({ queryKey: parkingKeys.sessionHistoryRoot() });
  });

  it('cancel clears the active-session cache and refreshes history', async () => {
    const sdk = createSdk();
    const client = createTestQueryClient();
    client.setQueryData(parkingKeys.activeSession(), activeSession);
    const invalidate = vi.spyOn(client, 'invalidateQueries');
    const options = createCancelParkingSessionMutationOptions(sdk, client);

    const result = await options.mutationFn(sessionId);
    await options.onSuccess(result);

    expect(sdk.parkingApi.cancelParkingSession).toHaveBeenCalledOnce();
    expect(client.getQueryData(parkingKeys.activeSession())).toBeNull();
    expect(invalidate).toHaveBeenCalledWith({ queryKey: parkingKeys.sessionHistoryRoot() });
  });

  it('does not clear active-session when a terminal call unexpectedly returns ACTIVE', async () => {
    const sdk = createSdk();
    const client = createTestQueryClient();
    client.setQueryData(parkingKeys.activeSession(), activeSession);
    const options = createCompleteParkingSessionMutationOptions(sdk, client);

    await options.onSuccess(activeSession);

    expect(client.getQueryData(parkingKeys.activeSession())).toEqual(activeSession);
  });

  it('delete session invalidates history and preserves the active entry', async () => {
    const sdk = createSdk();
    const client = createTestQueryClient();
    client.setQueryData(parkingKeys.activeSession(), activeSession);
    const invalidate = vi.spyOn(client, 'invalidateQueries');
    const options = createDeleteParkingSessionMutationOptions(sdk, client);

    await options.mutationFn(sessionId);
    await options.onSuccess();

    expect(sdk.parkingApi.deleteParkingSession).toHaveBeenCalledWith(sessionId);
    expect(invalidate).toHaveBeenCalledWith({ queryKey: parkingKeys.sessionHistoryRoot() });
    expect(client.getQueryData(parkingKeys.activeSession())).toEqual(activeSession);
  });

  it('delete history invalidates the history root only', async () => {
    const sdk = createSdk();
    const client = createTestQueryClient();
    const invalidate = vi.spyOn(client, 'invalidateQueries');
    const options = createDeleteParkingSessionHistoryMutationOptions(sdk, client);

    await options.mutationFn();
    await options.onSuccess();

    expect(sdk.parkingApi.deleteParkingSessionHistory).toHaveBeenCalledOnce();
    expect(invalidate).toHaveBeenCalledWith({ queryKey: parkingKeys.sessionHistoryRoot() });
  });
});
