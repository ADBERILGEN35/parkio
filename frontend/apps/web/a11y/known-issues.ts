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

export const KNOWN_ISSUES: KnownIssue[] = [
  {
    page: 'explore',
    rule: 'target-size',
    target: 'summary',
    reason:
      "MapLibre's attribution toggle (summary.maplibregl-ctrl-attrib-button, bottom-right) sits under the " +
      'floating zoom rail on /explore at desktop width, leaving 24x6 px of it clickable (WCAG 2.5.8). ' +
      'Measured once the public-explore flag is on (#229 review N2 follow-up); the layout fix is a separate change.',
  },
  {
    page: 'explore',
    rule: 'target-size',
    target: 'maplibre.org',
    reason:
      "The release image's map shows MapLibre's attribution text, whose 'MapLibre' link is 55x14 px. It is " +
      "an inline link inside the attribution line, which WCAG 2.5.8 exempts, but axe measures it as a target. " +
      'Recorded with the attribution toggle above; the dev server, without a MapTiler key, does not show it.',
  },
  {
    page: 'explore',
    rule: 'target-size',
    target: 'button[data-facility-id',
    reason:
      'Facility markers overlap until the explore map has framed them: at the starting zoom two car parks ' +
      'about 1.4 km apart cover each other (40x2 px left clickable, WCAG 2.5.8). Whether axe runs before or ' +
      'after the framing is timing, so it is reported when seen. The marker layout fix is a separate change.',
  },
];
