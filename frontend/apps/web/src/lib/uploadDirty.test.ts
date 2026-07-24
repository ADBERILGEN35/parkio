import { describe, expect, it } from 'vitest';
import { isAuthEscapePath, isUploadWizardDirty } from './uploadDirty';

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
