const CHANNEL_NAME = 'parkio.auth';
const MESSAGE_VERSION = 1;
const SESSION_DESTRUCTION_TYPE = 'session-destroyed';
const MAX_SEEN_EVENTS = 100;

export interface SessionDestructionMessage {
  readonly version: 1;
  readonly type: 'session-destroyed';
  readonly eventId: string;
}

interface CrossTabChannel {
  postMessage(message: unknown): void;
  addEventListener(type: 'message', listener: (event: MessageEvent<unknown>) => void): void;
  removeEventListener(type: 'message', listener: (event: MessageEvent<unknown>) => void): void;
  close?(): void;
}

export interface CrossTabSessionSync {
  broadcastSessionDestruction(): void;
  subscribe(onRemoteSessionDestruction: () => void): () => void;
  dispose(): void;
}

export interface CrossTabSessionSyncOptions {
  readonly createChannel?: (name: string) => CrossTabChannel | null;
  readonly createEventId?: () => string;
}

function defaultChannelFactory(name: string): CrossTabChannel | null {
  if (typeof BroadcastChannel === 'undefined') {
    return null;
  }
  return new BroadcastChannel(name);
}

function defaultEventId(): string {
  if (typeof globalThis.crypto?.randomUUID === 'function') {
    return globalThis.crypto.randomUUID();
  }
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
}

function isSessionDestructionMessage(value: unknown): value is SessionDestructionMessage {
  if (!value || typeof value !== 'object') {
    return false;
  }
  const record = value as Record<string, unknown>;
  const keys = Object.keys(record);
  return (
    keys.length === 3 &&
    keys.every((key) => key === 'version' || key === 'type' || key === 'eventId') &&
    record.version === MESSAGE_VERSION &&
    record.type === SESSION_DESTRUCTION_TYPE &&
    typeof record.eventId === 'string' &&
    /^[A-Za-z0-9-]{1,128}$/.test(record.eventId)
  );
}

/**
 * Creates one credential-free cross-tab invalidation channel per application runtime.
 * Receiving a message never publishes another one; session state is restored independently
 * by each tab through the SDK lifecycle.
 */
export function createCrossTabSessionSync(
  options: CrossTabSessionSyncOptions = {},
): CrossTabSessionSync {
  const channel = (options.createChannel ?? defaultChannelFactory)(CHANNEL_NAME);
  const createEventId = options.createEventId ?? defaultEventId;
  const seenEventIds = new Set<string>();
  const listeners = new Set<() => void>();

  const remember = (eventId: string) => {
    seenEventIds.add(eventId);
    if (seenEventIds.size > MAX_SEEN_EVENTS) {
      const oldest = seenEventIds.values().next().value as string | undefined;
      if (oldest) {
        seenEventIds.delete(oldest);
      }
    }
  };

  const handleMessage = (event: MessageEvent<unknown>) => {
    if (!isSessionDestructionMessage(event.data) || seenEventIds.has(event.data.eventId)) {
      return;
    }
    remember(event.data.eventId);
    listeners.forEach((listener) => listener());
  };

  channel?.addEventListener('message', handleMessage);

  let disposed = false;
  return Object.freeze({
    broadcastSessionDestruction() {
      if (disposed || !channel) {
        return;
      }
      const eventId = createEventId();
      remember(eventId);
      channel.postMessage({
        version: MESSAGE_VERSION,
        type: SESSION_DESTRUCTION_TYPE,
        eventId,
      } satisfies SessionDestructionMessage);
    },

    subscribe(onRemoteSessionDestruction: () => void) {
      if (disposed) {
        return () => {};
      }
      listeners.add(onRemoteSessionDestruction);
      return () => listeners.delete(onRemoteSessionDestruction);
    },

    dispose() {
      if (disposed) {
        return;
      }
      disposed = true;
      channel?.removeEventListener('message', handleMessage);
      channel?.close?.();
      listeners.clear();
      seenEventIds.clear();
    },
  });
}
