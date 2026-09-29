/**
 * ASCII '/' helpers that avoid polynomial-time regular expressions.
 * CodeQL js/polynomial-redos flags `/^\/+|\/+$/g` and `/\/+$/` on
 * uncontrolled/library strings; these loops are strictly O(n).
 */

const SLASH = 47; // '/'

/** Strip leading and trailing ASCII '/' characters. */
export function trimAsciiSlashes(value: string): string {
  let start = 0;
  let end = value.length;
  while (start < end && value.charCodeAt(start) === SLASH) start += 1;
  while (end > start && value.charCodeAt(end - 1) === SLASH) end -= 1;
  return value.slice(start, end);
}

/** Strip trailing ASCII '/' characters only. */
export function trimTrailingAsciiSlashes(value: string): string {
  let end = value.length;
  while (end > 0 && value.charCodeAt(end - 1) === SLASH) end -= 1;
  return value.slice(0, end);
}
