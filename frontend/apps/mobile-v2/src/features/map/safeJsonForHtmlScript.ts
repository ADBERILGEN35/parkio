/**
 * Safe JSON embedding for values interpolated into HTML <script> blocks.
 * JSON.stringify alone does not escape </script>, U+2028, or U+2029.
 */
export function safeJsonForHtmlScript(value: unknown): string {
  return JSON.stringify(value)
    .replace(/</g, '\\u003c')
    .replace(/>/g, '\\u003e')
    .replace(/\u2028/g, '\\u2028')
    .replace(/\u2029/g, '\\u2029');
}

/**
 * Serialize a bridge command for injectJavaScript.
 * The executable template stays constant; only the JSON string is dynamic.
 */
export function buildParkioDispatchScript(payload: Record<string, unknown>): string {
  const json = JSON.stringify(payload);
  // Double-encode so the WebView receives a string literal for JSON.parse.
  return `window.__parkio_dispatch(${JSON.stringify(json)}); true;`;
}
