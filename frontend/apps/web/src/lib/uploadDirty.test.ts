import { describe, expect, it } from 'vitest';
import { hasFormChanges, isAuthEscapePath, isUploadWizardDirty } from './uploadDirty';

describe('isUploadWizardDirty', () => {
  const clean = {
    hasSucceeded: false,
    hasSelectedFile: false,
    hasUploadedMedia: false,
    formIsDirty: false,
    hasLocationLabel: false,
  };

  it('is clean when the wizard was only opened', () => {
    expect(isUploadWizardDirty(clean)).toBe(false);
  });

  it('is dirty when a photo is selected', () => {
    expect(isUploadWizardDirty({ ...clean, hasSelectedFile: true })).toBe(true);
  });

  it('is dirty when media was uploaded (retry path)', () => {
    expect(isUploadWizardDirty({ ...clean, hasUploadedMedia: true })).toBe(true);
  });

  it('is dirty when the form has meaningful field edits', () => {
    expect(isUploadWizardDirty({ ...clean, formIsDirty: true })).toBe(true);
  });

  it('is dirty when a location label was set', () => {
    expect(isUploadWizardDirty({ ...clean, hasLocationLabel: true })).toBe(true);
  });

  it('is clean after successful submission even if other flags linger', () => {
    expect(
      isUploadWizardDirty({
        hasSucceeded: true,
        hasSelectedFile: true,
        hasUploadedMedia: true,
        formIsDirty: true,
        hasLocationLabel: true,
      }),
    ).toBe(false);
  });
});

describe('hasFormChanges', () => {
  const defaults = { addressText: '', manualLocationEdited: false, suitableVehicleTypes: [] as string[] };

  it('ignores fields that are undefined on one side and missing on the other', () => {
    expect(hasFormChanges({ ...defaults, latitude: undefined, legalStatus: undefined }, defaults)).toBe(false);
  });

  it('detects a changed text, flag or list', () => {
    expect(hasFormChanges({ ...defaults, addressText: '12 Curb Lane' }, defaults)).toBe(true);
    expect(hasFormChanges({ ...defaults, manualLocationEdited: true }, defaults)).toBe(true);
    expect(hasFormChanges({ ...defaults, suitableVehicleTypes: ['SEDAN'] }, defaults)).toBe(true);
  });

  it('detects a value set on a field that has no default', () => {
    expect(hasFormChanges({ ...defaults, latitude: 41.01 }, defaults)).toBe(true);
  });

  it('is clean again once a value returns to its default', () => {
    expect(hasFormChanges({ ...defaults, suitableVehicleTypes: [] }, defaults)).toBe(false);
  });
});

describe('isAuthEscapePath', () => {
  it('preserves the exact manifest-owned auth and preparing bypass set', () => {
    for (const pathname of [
      '/login',
      '/register',
      '/forgot-password',
      '/reset-password',
      '/check-email',
      '/verify-email',
      '/preparing',
    ]) {
      expect(isAuthEscapePath(pathname)).toBe(true);
    }
  });

  it('does not broaden bypass eligibility to other or malformed paths', () => {
    for (const pathname of [
      '/',
      '/terms',
      '/privacy',
      '/map',
      '/upload',
      '/admin',
      '/unknown',
      '/login/',
      '/login?return=/map',
    ]) {
      expect(isAuthEscapePath(pathname)).toBe(false);
    }
  });
});
