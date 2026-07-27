/** Monotonic token for in-flight camera/gallery picks within one share session. */
let mediaSelectionSeq = 0;

/** Mark the start of a new camera/gallery selection; stale async work must not apply. */
export function beginMediaSelection(): number {
  mediaSelectionSeq += 1;
  return mediaSelectionSeq;
}

/** True when `token` is still the newest started media selection. */
export function isLatestMediaSelection(token: number): boolean {
  return token === mediaSelectionSeq;
}

/** Test-only reset. */
export function resetMediaSelectionGuard(): void {
  mediaSelectionSeq = 0;
}