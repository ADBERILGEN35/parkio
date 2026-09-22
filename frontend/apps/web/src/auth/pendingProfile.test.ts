import { afterEach, describe, expect, it } from 'vitest';
import {
  clearPendingProfile,
  getPendingProfile,
  hasPendingProfile,
  pendingProfileStorageContainsPhoneForTests,
  resetPendingProfileMemoryForTests,
  setPendingProfile,
} from './pendingProfile';

const STORAGE_KEY = 'parkio.pendingProfile';

afterEach(() => {
  clearPendingProfile();
});

describe('pendingProfile', () => {
  it('keeps phoneNumber in memory but never in sessionStorage', () => {
    setPendingProfile({ displayName: 'Ada', phoneNumber: '5551234567' });

    expect(getPendingProfile()).toEqual({
      displayName: 'Ada',
      phoneNumber: '5551234567',
    });
    expect(pendingProfileStorageContainsPhoneForTests()).toBe(false);
    expect(JSON.parse(sessionStorage.getItem(STORAGE_KEY)!)).toEqual({
      displayName: 'Ada',
      needsPhoneReentry: true,
    });
  });

  it('persists displayName alone across a simulated reload and marks phone re-entry', () => {
    setPendingProfile({ displayName: 'Ada', phoneNumber: '5551234567' });
    // Full page reload: module memory is gone; sessionStorage remains.
    resetPendingProfileMemoryForTests();

    expect(getPendingProfile()).toEqual({
      displayName: 'Ada',
      phoneNumber: undefined,
      needsPhoneReentry: true,
    });
    expect(pendingProfileStorageContainsPhoneForTests()).toBe(false);
  });

  it('scrubs legacy phoneNumber from sessionStorage on read and requests re-entry', () => {
    sessionStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({ displayName: 'Legacy', phoneNumber: '5559998888' }),
    );

    expect(getPendingProfile()).toEqual({
      displayName: 'Legacy',
      phoneNumber: undefined,
      needsPhoneReentry: true,
    });
    expect(pendingProfileStorageContainsPhoneForTests()).toBe(false);
    expect(JSON.parse(sessionStorage.getItem(STORAGE_KEY)!)).toEqual({
      displayName: 'Legacy',
      needsPhoneReentry: true,
    });
  });

  it('removes malformed and empty payloads safely', () => {
    sessionStorage.setItem(STORAGE_KEY, '{not-json');
    expect(getPendingProfile()).toBeNull();
    expect(sessionStorage.getItem(STORAGE_KEY)).toBeNull();

    sessionStorage.setItem(STORAGE_KEY, JSON.stringify({ phoneNumber: '555' }));
    // Legacy phone-only payload → scrubbed to re-entry flag only (no phone value).
    expect(getPendingProfile()).toEqual({
      phoneNumber: undefined,
      needsPhoneReentry: true,
    });
    expect(JSON.parse(sessionStorage.getItem(STORAGE_KEY)!)).toEqual({
      needsPhoneReentry: true,
    });
    clearPendingProfile();

    sessionStorage.setItem(STORAGE_KEY, JSON.stringify([]));
    expect(getPendingProfile()).toBeNull();
    expect(sessionStorage.getItem(STORAGE_KEY)).toBeNull();
  });

  it('clears both memory and storage', () => {
    setPendingProfile({ displayName: 'Ada', phoneNumber: '5551234567' });
    clearPendingProfile();
    expect(getPendingProfile()).toBeNull();
    expect(sessionStorage.getItem(STORAGE_KEY)).toBeNull();
  });

  it('hasPendingProfile is true for phone-only in-memory state', () => {
    setPendingProfile({ phoneNumber: '5551234567' });
    const pending = getPendingProfile();
    expect(hasPendingProfile(pending)).toBe(true);
    expect(pending?.phoneNumber).toBe('5551234567');
    expect(JSON.parse(sessionStorage.getItem(STORAGE_KEY)!)).toEqual({
      needsPhoneReentry: true,
    });
  });

  it('setPendingProfile overwrites and does not leave prior phone in storage', () => {
    sessionStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({ displayName: 'Old', phoneNumber: '111' }),
    );
    setPendingProfile({ displayName: 'New', phoneNumber: '222' });
    expect(JSON.parse(sessionStorage.getItem(STORAGE_KEY)!)).toEqual({
      displayName: 'New',
      needsPhoneReentry: true,
    });
    expect(getPendingProfile()?.phoneNumber).toBe('222');
  });
});
