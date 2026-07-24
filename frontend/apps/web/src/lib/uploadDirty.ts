import { isNavigationInterruptionBypassPath } from '@/routing/route-manifest';

/**
 * Pure dirty-state rules for the /upload wizard.
 * Step index alone is never enough — meaningful edits on step 1 must count.
 */
export type UploadDirtyInput = {
  /** Spot was created successfully; leave without warning. */
  hasSucceeded: boolean;
  hasSelectedFile: boolean;
  hasUploadedMedia: boolean;
  /** react-hook-form formState.isDirty */
  formIsDirty: boolean;
  /** Human-readable pin label (place search or map point). */
  hasLocationLabel: boolean;
};

export function isUploadWizardDirty(input: UploadDirtyInput): boolean {
  if (input.hasSucceeded) return false;
  return (
    input.hasSelectedFile ||
    input.hasUploadedMedia ||
    input.formIsDirty ||
    input.hasLocationLabel
  );
}

/** Auth / security redirects must never be trapped by the unsaved-changes dialog. */
export function isAuthEscapePath(pathname: string): boolean {
  return isNavigationInterruptionBypassPath(pathname);
}
