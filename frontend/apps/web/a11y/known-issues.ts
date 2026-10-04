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
    target: 'a[href$="maplibre.org/"]',
    html: 'href="https://maplibre.org/"',
    // Plain "insufficient size" at the attribution's line height. The width follows the font, so it is
    // not pinned.
    check: { height: 14 },
    reason:
      "The release image's map attribution line credits MapLibre with a 14 px high link, next to the map data " +
      "credits that MapTiler and OpenStreetMap require. WCAG 2.5.8 exempts it as an inline target constrained by " +
      'the line height of the surrounding text; axe cannot tell, so it is listed here (Asana 1219147334320125). ' +
      'The dev server, without a MapTiler key, shows the link but axe does not report it there.',
  },
];
