import { afterEach, describe, expect, it } from 'vitest';
import {
  claimPendingProfileFor,
  clearPendingProfile,
  getPendingProfile,
  getPendingProfileFor,
  hasPendingProfile,
  pendingProfileStorageContainsPhoneForTests,
  resetPendingProfileMemoryForTests,
  setPendingProfile,
} from './pendingProfile';

const STORAGE_KEY = 'parkio.pendingProfile';
const OWNER = '1a1a1a1a-0000-4000-8000-00000000000a';
const OTHER = '2b2b2b2b-0000-4000-8000-00000000000b';

afterEach(() => {
  clearPendingProfile();
});

describe('pendingProfile', () => {
  it('keeps phoneNumber in memory but never in sessionStorage', () => {
    setPendingProfile({ displayName: 'Ada', phoneNumber: '5551234567' }, OWNER);

    expect(getPendingProfile()).toEqual({
      ownerUserId: OWNER,
      displayName: 'Ada',
      phoneNumber: '5551234567',
    });
    expect(pendingProfileStorageContainsPhoneForTests()).toBe(false);
    expect(JSON.parse(sessionStorage.getItem(STORAGE_KEY)!)).toEqual({
      ownerUserId: OWNER,
      displayName: 'Ada',
      needsPhoneReentry: true,
    });
  });

  it('persists displayName alone across a simulated reload and marks phone re-entry', () => {
    setPendingProfile({ displayName: 'Ada', phoneNumber: '5551234567' }, OWNER);
    // Full page reload: module memory is gone; sessionStorage remains.
    resetPendingProfileMemoryForTests();

    expect(getPendingProfile()).toEqual({
      ownerUserId: OWNER,
      displayName: 'Ada',
      phoneNumber: undefined,
      needsPhoneReentry: true,
    });
    expect(pendingProfileStorageContainsPhoneForTests()).toBe(false);
  });

  it('scrubs a legacy phoneNumber from an owned payload and requests re-entry', () => {
    sessionStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({ ownerUserId: OWNER, displayName: 'Legacy', phoneNumber: '5559998888' }),
    );

    expect(getPendingProfile()).toEqual({
      ownerUserId: OWNER,
      displayName: 'Legacy',
      phoneNumber: undefined,
      needsPhoneReentry: true,
    });
    expect(pendingProfileStorageContainsPhoneForTests()).toBe(false);
    expect(JSON.parse(sessionStorage.getItem(STORAGE_KEY)!)).toEqual({
      ownerUserId: OWNER,
      displayName: 'Legacy',
      needsPhoneReentry: true,
    });
  });

  it('removes ownerless legacy payloads instead of attributing them to anyone', () => {
    sessionStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({ displayName: 'Legacy', phoneNumber: '5559998888' }),
    );
    expect(getPendingProfile()).toBeNull();
    expect(sessionStorage.getItem(STORAGE_KEY)).toBeNull();

    sessionStorage.setItem(STORAGE_KEY, JSON.stringify({ displayName: 'Legacy' }));
    expect(getPendingProfileFor(OWNER)).toBeNull();
    expect(sessionStorage.getItem(STORAGE_KEY)).toBeNull();
  });

  it('removes malformed and empty payloads safely', () => {
    sessionStorage.setItem(STORAGE_KEY, '{not-json');
    expect(getPendingProfile()).toBeNull();
    expect(sessionStorage.getItem(STORAGE_KEY)).toBeNull();

    sessionStorage.setItem(STORAGE_KEY, JSON.stringify({ ownerUserId: OWNER, phoneNumber: '555' }));
    // Legacy phone-only payload → scrubbed to re-entry flag only (no phone value).
    expect(getPendingProfile()).toEqual({
      ownerUserId: OWNER,
      phoneNumber: undefined,
      needsPhoneReentry: true,
    });
    expect(JSON.parse(sessionStorage.getItem(STORAGE_KEY)!)).toEqual({
      ownerUserId: OWNER,
      needsPhoneReentry: true,
    });
    clearPendingProfile();

    sessionStorage.setItem(STORAGE_KEY, JSON.stringify({ ownerUserId: 42, displayName: 'X' }));
    expect(getPendingProfile()).toBeNull();
    expect(sessionStorage.getItem(STORAGE_KEY)).toBeNull();

    sessionStorage.setItem(STORAGE_KEY, JSON.stringify([]));
    expect(getPendingProfile()).toBeNull();
    expect(sessionStorage.getItem(STORAGE_KEY)).toBeNull();
  });

  it('stores nothing when the registration identity is missing', () => {
    setPendingProfile({ displayName: 'Ada', phoneNumber: '5551234567' }, '  ');
    expect(getPendingProfile()).toBeNull();
    expect(sessionStorage.getItem(STORAGE_KEY)).toBeNull();
  });

  it('clears both memory and storage', () => {
    setPendingProfile({ displayName: 'Ada', phoneNumber: '5551234567' }, OWNER);
    clearPendingProfile();
    expect(getPendingProfile()).toBeNull();
    expect(sessionStorage.getItem(STORAGE_KEY)).toBeNull();
  });

  it('hasPendingProfile is true for phone-only in-memory state', () => {
    setPendingProfile({ phoneNumber: '5551234567' }, OWNER);
    const pending = getPendingProfile();
    expect(hasPendingProfile(pending)).toBe(true);
    expect(pending?.phoneNumber).toBe('5551234567');
    expect(JSON.parse(sessionStorage.getItem(STORAGE_KEY)!)).toEqual({
      ownerUserId: OWNER,
      needsPhoneReentry: true,
    });
  });

  it('setPendingProfile overwrites and does not leave prior phone in storage', () => {
    sessionStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({ displayName: 'Old', phoneNumber: '111' }),
    );
    setPendingProfile({ displayName: 'New', phoneNumber: '222' }, OWNER);
    expect(JSON.parse(sessionStorage.getItem(STORAGE_KEY)!)).toEqual({
      ownerUserId: OWNER,
      displayName: 'New',
      needsPhoneReentry: true,
    });
    expect(getPendingProfile()?.phoneNumber).toBe('222');
  });

  it('getPendingProfileFor returns the owner\'s profile and discards it for another account', () => {
    setPendingProfile({ displayName: 'Ada', phoneNumber: '5551234567' }, OWNER);
    expect(getPendingProfileFor(OWNER)?.phoneNumber).toBe('5551234567');

    expect(getPendingProfileFor(OTHER)).toBeNull();
    expect(getPendingProfile()).toBeNull();
    expect(sessionStorage.getItem(STORAGE_KEY)).toBeNull();
    // The discarded phone cannot resurface for the original owner either.
    expect(getPendingProfileFor(OWNER)).toBeNull();
  });

  it('getPendingProfileFor discards pending data when no identity is known', () => {
    setPendingProfile({ displayName: 'Ada' }, OWNER);
    expect(getPendingProfileFor(null)).toBeNull();
    expect(getPendingProfile()).toBeNull();
  });

  it('claimPendingProfileFor hands the profile out exactly once', () => {
    setPendingProfile({ displayName: 'Ada', phoneNumber: '5551234567' }, OWNER);
    expect(claimPendingProfileFor(OWNER)).toEqual({
      ownerUserId: OWNER,
      displayName: 'Ada',
      phoneNumber: '5551234567',
    });
    expect(claimPendingProfileFor(OWNER)).toBeNull();
    expect(sessionStorage.getItem(STORAGE_KEY)).toBeNull();
  });

  it('never pairs an in-memory phone with a different owner\'s persisted record', () => {
    setPendingProfile({ displayName: 'Ada', phoneNumber: '5551234567' }, OWNER);
    // Another tab-state writer (e.g. duplicated tab storage) replaces only storage.
    sessionStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({ ownerUserId: OTHER, displayName: 'Other' }),
    );
    expect(getPendingProfileFor(OTHER)).toEqual({
      ownerUserId: OTHER,
      displayName: 'Other',
      phoneNumber: undefined,
    });
  });
});
