/**
 * UUID v4 that works across every runtime we ship to.
 *
 * The web build uses the native Web Crypto `randomUUID`. React Native (Hermes)
 * has no global `crypto`, so we fall back to `getRandomValues` when a polyfill
 * provides it, and finally to a non-cryptographic generator. These ids are only
 * used for request correlation and idempotency keys — uniqueness, not
 * unpredictability, is what matters — so the fallback is safe.
 */
export function randomUUID(): string {
  const webCrypto = (globalThis as { crypto?: Crypto }).crypto;
  if (typeof webCrypto?.randomUUID === 'function') {
    return webCrypto.randomUUID();
  }

  const bytes = new Uint8Array(16);
  if (typeof webCrypto?.getRandomValues === 'function') {
    webCrypto.getRandomValues(bytes);
  } else {
    for (let i = 0; i < 16; i += 1) {
      bytes[i] = Math.floor(Math.random() * 256);
    }
  }
  bytes[6] = (bytes[6] & 0x0f) | 0x40; // version 4
  bytes[8] = (bytes[8] & 0x3f) | 0x80; // variant 10xx

  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0'));
  return `${hex.slice(0, 4).join('')}-${hex.slice(4, 6).join('')}-${hex.slice(6, 8).join('')}-${hex.slice(8, 10).join('')}-${hex.slice(10, 16).join('')}`;
}
