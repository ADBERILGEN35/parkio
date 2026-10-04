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
  /** A form value differs from its default ({@link hasFormChanges}). */
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

/**
 * True when a form value differs from its default value. A key that is undefined on one side and
 * missing on the other counts as unset. react-hook-form adds the fields that have no default to its
 * values as undefined, and under React StrictMode (the dev app) its formState.isDirty then turns
 * true although nothing was edited; this comparison does not.
 */
export function hasFormChanges(values: object, defaults: object): boolean {
  const current = values as Record<string, unknown>;
  const initial = defaults as Record<string, unknown>;
  for (const key of new Set([...Object.keys(current), ...Object.keys(initial)])) {
    if (!isSameFormValue(current[key], initial[key])) return true;
  }
  return false;
}

function isSameFormValue(a: unknown, b: unknown): boolean {
  if (Object.is(a, b)) return true;
  if (Array.isArray(a) || Array.isArray(b)) {
    return (
      Array.isArray(a) &&
      Array.isArray(b) &&
      a.length === b.length &&
      a.every((item, index) => isSameFormValue(item, b[index]))
    );
  }
  // Object.is: two invalid Dates (NaN time) are the same value too.
  if (a instanceof Date && b instanceof Date) return Object.is(a.getTime(), b.getTime());
  if (isPlainObject(a) && isPlainObject(b)) return !hasFormChanges(a, b);
  // Any other object (a File, a Blob) is the same value only when it is the same instance.
  return false;
}

function isPlainObject(value: unknown): value is object {
  if (value === null || typeof value !== 'object') return false;
  const prototype = Object.getPrototypeOf(value);
  return prototype === Object.prototype || prototype === null;
}

/** Auth / security redirects must never be trapped by the unsaved-changes dialog. */
export function isAuthEscapePath(pathname: string): boolean {
  return isNavigationInterruptionBypassPath(pathname);
}
