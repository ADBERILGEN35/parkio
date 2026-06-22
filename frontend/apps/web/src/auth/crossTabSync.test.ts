import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * Minimal in-process BroadcastChannel stand-in: delivers a posted message to all
 * *other* instances with the same name (never to the sender), matching the real
 * cross-tab semantics we rely on.
 */
class FakeBroadcastChannel {
  static instances: FakeBroadcastChannel[] = [];
  private listeners = new Set<(e: MessageEvent) => void>();
  constructor(public name: string) {
    FakeBroadcastChannel.instances.push(this);
  }
  postMessage(data: unknown) {
    for (const inst of FakeBroadcastChannel.instances) {
      if (inst !== this && inst.name === this.name) {
        inst.listeners.forEach((l) => l({ data } as MessageEvent));
      }
    }
  }
  addEventListener(_type: 'message', listener: (e: MessageEvent) => void) {
    this.listeners.add(listener);
  }
  removeEventListener(_type: 'message', listener: (e: MessageEvent) => void) {
    this.listeners.delete(listener);
  }
}

async function loadModule() {
  vi.resetModules();
  return import('./crossTabSync');
}

beforeEach(() => {
  FakeBroadcastChannel.instances = [];
  vi.stubGlobal('BroadcastChannel', FakeBroadcastChannel);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('crossTabSync', () => {
  it('invokes the remote-logout handler when another tab broadcasts logout', async () => {
    const { initCrossTabAuth } = await loadModule();
    const onRemoteLogout = vi.fn();
    initCrossTabAuth(onRemoteLogout);

    // Simulate "another tab" posting a logout on the same channel name.
    new FakeBroadcastChannel('parkio.auth').postMessage({ type: 'logout' });

    expect(onRemoteLogout).toHaveBeenCalledTimes(1);
  });

  it('broadcastLogout posts a logout message other tabs receive', async () => {
    const { broadcastLogout } = await loadModule();
    const otherTab = new FakeBroadcastChannel('parkio.auth');
    const received: unknown[] = [];
    otherTab.addEventListener('message', (e) => received.push(e.data));

    broadcastLogout();

    expect(received).toEqual([{ type: 'logout' }]);
  });

  it('unsubscribe stops handling further logout broadcasts', async () => {
    const { initCrossTabAuth } = await loadModule();
    const onRemoteLogout = vi.fn();
    const unsubscribe = initCrossTabAuth(onRemoteLogout);

    unsubscribe();
    new FakeBroadcastChannel('parkio.auth').postMessage({ type: 'logout' });

    expect(onRemoteLogout).not.toHaveBeenCalled();
  });

  it('degrades to a no-op when BroadcastChannel is unavailable', async () => {
    vi.stubGlobal('BroadcastChannel', undefined);
    const { broadcastLogout, initCrossTabAuth } = await loadModule();

    const unsubscribe = initCrossTabAuth(vi.fn());
    expect(() => broadcastLogout()).not.toThrow();
    expect(() => unsubscribe()).not.toThrow();
  });
});
