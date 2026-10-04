/**
 * Measured violations that this change documents instead of fixing (CL-F30: "confirmed issues fixed
 * or documented"). Each entry needs a reason and stays visible in the report as a known violation.
 *
 * An entry matches only the exact node it documents (#242 review N1): the whole axe selector (a
 * string, or an anchored RegExp), a piece of the node's HTML and, where given, the failing check's
 * message key, size and related node. Another node, or a worse failure of the same node, still fails
 * the suite. Entries that no page matched are listed after each run.
 */
export interface KnownIssue {
  /** A page name from the specs, or '*' for every page. */
  page: string;
  /** The axe rule id. */
  rule: string;
  /** The whole axe node selector. */
  target: string | RegExp;
  /** Text the node's HTML must contain, for example its class. */
  html?: string;
  /** The rule's own failing check: every field given here must match. */
  check?: {
    /** axe's message key, for example 'partiallyObscured'; undefined means plain "insufficient size". */
    messageKey?: string;
    width?: number;
    height?: number;
    /** Text the HTML of the node axe names as the cause (for example the obscuring element) contains. */
    relatedHtml?: string;
  };
  reason: string;
}

export const KNOWN_ISSUES: KnownIssue[] = [
  {
    page: 'explore',
    rule: 'target-size',
    target: 'summary',
    html: 'maplibregl-ctrl-attrib-button',
    check: { messageKey: 'partiallyObscured', width: 24, height: 6, relatedHtml: 'data-testid="map-floating-zoom-out"' },
    reason:
      "MapLibre's attribution toggle (bottom-right) sits under the floating zoom rail's zoom-out button on " +
      '/explore at desktop width, leaving 24x6 px of it clickable (WCAG 2.5.8). Measured once the public-explore ' +
      'flag is on (#229 review N2 follow-up). Fix: Asana 1219147334320125.',
  },
  {
    page: 'explore',
    rule: 'target-size',
    target: 'a[href$="maplibre.org/"]',
    html: 'href="https://maplibre.org/"',
    // Plain "insufficient size" at the attribution's line height. The width follows the font, so it is
    // not pinned.
    check: { height: 14 },
    reason:
      "The release image's map shows MapLibre's attribution text, whose 'MapLibre' link is 14 px high. It is " +
      'an inline link inside the attribution line, which WCAG 2.5.8 exempts, but axe measures it as a target. ' +
      'Recorded with the attribution toggle above (Asana 1219147334320125); the dev server, without a ' +
      'MapTiler key, does not show it.',
  },
  {
    page: 'explore',
    rule: 'target-size',
    target: /^button\[data-facility-id="a11y-facility-[12]"\]$/,
    html: 'data-testid="municipal-facility-marker"',
    // Covered by the other synthetic car park, not too small on its own; the size depends on the zoom.
    check: { messageKey: 'partiallyObscured', relatedHtml: 'data-testid="municipal-facility-marker"' },
    reason:
      'Facility markers overlap until the explore map has framed them: at the starting zoom the two synthetic ' +
      'car parks, about 1.4 km apart, cover each other (WCAG 2.5.8). Whether axe runs before or after the ' +
      'framing is timing, so this entry is reported when seen. Fix: Asana 1219147334320125.',
  },
];
