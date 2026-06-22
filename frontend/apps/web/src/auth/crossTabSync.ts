/**
 * Cross-tab auth coordination.
 *
 * Single-flight refresh is per-tab only — it cannot stop a *second tab* from
 * presenting the same HttpOnly refresh cookie. What we can do cheaply is keep
 * logout consistent: when one tab signs out (explicit logout, or a refresh
 * failure that clears the session), every other tab drops its in-memory session
 * too, instead of lingering on a now-revoked token until its next 401.
 *
 * Security: only a "logged out" signal crosses the channel. No access token and
 * no refresh token is ever broadcast or stored — the refresh token stays in the
 * HttpOnly cookie and the access token stays in memory in each tab.
 */
const CHANNEL_NAME = 'parkio.auth';

type AuthBroadcast = { type: 'logout' };

let channel: BroadcastChannel | null = null;

function getChannel(): BroadcastChannel | null {
  if (typeof BroadcastChannel === 'undefined') {
    return null; // jsdom/older browsers — degrade to in-tab only.
  }
  channel ??= new BroadcastChannel(CHANNEL_NAME);
  return channel;
}

/** Tell other tabs this browser session was signed out. */
export function broadcastLogout(): void {
  getChannel()?.postMessage({ type: 'logout' } satisfies AuthBroadcast);
}

/**
 * Subscribe to remote logout events. BroadcastChannel does not echo a message
 * back to its sender, so the handler only fires for *other* tabs — no loop.
 * Returns an unsubscribe function.
 */
export function initCrossTabAuth(onRemoteLogout: () => void): () => void {
  const ch = getChannel();
  if (!ch) {
    return () => {};
  }
  const handler = (event: MessageEvent<AuthBroadcast>) => {
    if (event.data?.type === 'logout') {
      onRemoteLogout();
    }
  };
  ch.addEventListener('message', handler);
  return () => ch.removeEventListener('message', handler);
}
