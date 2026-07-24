import type { SmartReturnSettings } from '@parkio/types';
import { describe, expect, it, vi } from 'vitest';
import type { ParkioSdk } from '@/app/sdk';
import { meKeys } from '@/data/keys';
import { createTestQueryClient } from '@/test/utils';
import {
  applySmartReturnSettings,
  createCancelSmartReturnTodayMutationOptions,
  createSmartReturnLeftByCarMutationOptions,
  createUpdateSmartReturnSettingsMutationOptions,
} from './smart-return';

const settings: SmartReturnSettings = {
  enabled: true,
  homeLatitude: 41,
  homeLongitude: 29,
  homeLabel: 'Home',
  defaultReturnTime: '18:30',
  reminderLeadMinutes: 15,
  lastPromptDate: null,
  todayStatus: 'UNKNOWN',
  todayExpectedReturnAt: null,
  todayReturnCheckCompletedAt: null,
  todayNotificationSentAt: null,
};

function createSdk(next: SmartReturnSettings): ParkioSdk {
  return {
    usersApi: {
      smartReturnLeftByCar: vi.fn(async () => next),
      cancelSmartReturnToday: vi.fn(async () => next),
      updateSmartReturnSettings: vi.fn(async () => next),
    },
  } as unknown as ParkioSdk;
}

describe('smart return mutation options', () => {
  it('writes canonical meKeys.smartReturn() on plan success', async () => {
    const next = { ...settings, todayStatus: 'LEFT_BY_CAR' as const };
    const sdk = createSdk(next);
    const client = createTestQueryClient();
    client.setQueryData(meKeys.smartReturn(), settings);
    const options = createSmartReturnLeftByCarMutationOptions(sdk, client);
    const result = await options.mutationFn({ expectedReturnAt: '2026-07-24T18:30:00.000Z' });
    options.onSuccess(result);
    expect(client.getQueryData(meKeys.smartReturn())).toEqual(next);
  });

  it('cancel and settings updates share applySmartReturnSettings', async () => {
    const client = createTestQueryClient();
    applySmartReturnSettings(client, settings);
    expect(client.getQueryData(meKeys.smartReturn())).toEqual(settings);

    const cancelled = { ...settings, todayStatus: 'CANCELLED' as const };
    const sdk = createSdk(cancelled);
    const cancel = createCancelSmartReturnTodayMutationOptions(sdk, client);
    cancel.onSuccess(await cancel.mutationFn());
    expect(client.getQueryData(meKeys.smartReturn())).toEqual(cancelled);

    const updated = { ...settings, reminderLeadMinutes: 30 };
    const updateSdk = createSdk(updated);
    const update = createUpdateSmartReturnSettingsMutationOptions(updateSdk, client);
    update.onSuccess(
      await update.mutationFn({ enabled: true, reminderLeadMinutes: 30 }),
    );
    expect(client.getQueryData(meKeys.smartReturn())).toEqual(updated);
  });
});