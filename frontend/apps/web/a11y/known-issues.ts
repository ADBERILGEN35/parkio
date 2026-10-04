/**
 * Measured violations that this change documents instead of fixing (CL-F30: "confirmed issues fixed
 * or documented"). Each entry needs a reason and stays visible in the report as a known violation.
 * `page` is a page name from the specs, or '*' for every page; `target` is a substring of the axe
 * node selector.
 */
export interface KnownIssue {
  page: string;
  rule: string;
  target: string;
  reason: string;
}

export const KNOWN_ISSUES: KnownIssue[] = [];
