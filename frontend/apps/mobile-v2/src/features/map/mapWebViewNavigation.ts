/**
 * Top-level WebView navigation policy for the inline MapLibre document.
 *
 * The map document is loaded via `source={{ html }}` (about:blank origin).
 * Tile/style subresources are not top-level navigations.
 * External Google Maps handoff stays in native Linking — never via WebView nav.
 */

export type MapNavigationRequest = {
  url: string;
  isTopFrame?: boolean;
};

/**
 * Allow only the initial inline HTML document. Block unexpected top-level loads
 * including https/http/javascript/data/file/custom schemes.
 */
export function shouldAllowMapWebViewNavigation(request: MapNavigationRequest): boolean {
  // Deny nested frames — this document does not host iframes.
  if (request.isTopFrame === false) {
    return false;
  }

  const url = typeof request.url === 'string' ? request.url : '';

  // First load of source={{ html }} — platforms report about:blank / blank / empty.
  if (url === '' || url === 'about:blank' || url === 'about:srcdoc') {
    return true;
  }

  return false;
}

/** Window.open / target=_blank — always blocked for this map surface. */
export function shouldAllowMapWebViewOpenWindow(): boolean {
  return false;
}
