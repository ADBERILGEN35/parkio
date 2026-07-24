import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  createCrossTabSessionSync,
  type SessionDestructionMessage,
} from './crossTabSync';

class FakeBroadcastChannel {
  static instances: FakeBroadcastChannel[] = [];
  readonly posted: unknown[] = [];
  private readonly listeners = new Set<(event: MessageEvent<unknown>) => void>();

  constructor(readonly name: string) {
    FakeBroadcastChannel.instances.push(this);
  }

  postMessage(data: unknown) {
    this.posted.push(data);
    for (const instance of FakeBroadcastChannel.instances) {
      if (instance !== this && instance.name === this.name) {
        instance.deliver(data);
      }
    }
  }

  addEventListener(_type: 'message', listener: (event: MessageEvent<unknown>) => void) {
    this.listeners.add(listener);
  }

  removeEventListener(_type: 'message', listener: (event: MessageEvent<unknown>) => void) {
    this.listeners.delete(listener);
  }

  deliver(data: unknown) {
    this.listeners.forEach((listener) => listener({ data } as MessageEvent<unknown>));
  }

  close() {
    this.listeners.clear();
  }
}

function createSync(eventId = 'event-1') {
  return createCrossTabSessionSync({
    createChannel: (name) => new FakeBroadcastChannel(name),
    createEventId: () => eventId,
  });
}

beforeEach(() => {
  FakeBroadcastChannel.instances = [];
});

describe('credential-free cross-tab session destruction', () => {
  it('broadcasts only the versioned non-sensitive invalidation envelope', () => {
    const sync = createSync('event-safe-1');
    const receivingChannel = new FakeBroadcastChannel('parkio.auth');
    const received: unknown[] = [];
    receivingChannel.addEventListener('message', (event) => received.push(event.data));

    sync.broadcastSessionDestruction();

    expect(received).toEqual([
      { version: 1, type: 'session-destroyed', eventId: 'event-safe-1' },
    ]);
    const serialized = JSON.stringify(received);
    expect(serialized).not.toMatch(
      /accessToken|refreshToken|authorization|email|roles|user|coordinates|payload/i,
    );
  });

  it('handles a remote destruction event once', () => {
    const sync = createSync();
    const onRemoteDestruction = vi.fn();
    sync.subscribe(onRemoteDestruction);

    new FakeBroadcastChannel('parkio.auth').postMessage({
      version: 1,
      type: 'session-destroyed',
      eventId: 'remote-1',
    } satisfies SessionDestructionMessage);

    expect(onRemoteDestruction).toHaveBeenCalledTimes(1);
  });

  it('deduplicates repeated event identifiers', () => {
    const sync = createSync();
    const onRemoteDestruction = vi.fn();
    sync.subscribe(onRemoteDestruction);
    const ownChannel = FakeBroadcastChannel.instances[0]!;
    const message: SessionDestructionMessage = {
      version: 1,
      type: 'session-destroyed',
      eventId: 'duplicate-1',
    };

    ownChannel.deliver(message);
    ownChannel.deliver(message);

    expect(onRemoteDestruction).toHaveBeenCalledTimes(1);
  });

  it('does not echo a remotely received event', () => {
    const first = createSync('first-event');
    const second = createSync('second-event');
    const onSecond = vi.fn();
    second.subscribe(onSecond);

    first.broadcastSessionDestruction();

    expect(onSecond).toHaveBeenCalledTimes(1);
    expect(FakeBroadcastChannel.instances.flatMap((channel) => channel.posted)).toHaveLength(1);
  });

  it('rejects envelopes with credentials, identity, or backend payload fields', () => {
    const sync = createSync();
    const onRemoteDestruction = vi.fn();
    sync.subscribe(onRemoteDestruction);
    const ownChannel = FakeBroadcastChannel.instances[0]!;

    ownChannel.deliver({
      version: 1,
      type: 'session-destroyed',
      eventId: 'unsafe-1',
      accessToken: 'secret',
    });
    ownChannel.deliver({
      version: 1,
      type: 'session-destroyed',
      eventId: 'unsafe-2',
      user: { email: 'tester@parkio.dev' },
    });

    expect(onRemoteDestruction).not.toHaveBeenCalled();
  });

  it('unsubscribes, disposes, and degrades without BroadcastChannel', () => {
    const sync = createSync();
    const onRemoteDestruction = vi.fn();
    const unsubscribe = sync.subscribe(onRemoteDestruction);
    const ownChannel = FakeBroadcastChannel.instances[0]!;

    unsubscribe();
    ownChannel.deliver({ version: 1, type: 'session-destroyed', eventId: 'remote-2' });
    sync.dispose();
    expect(() => sync.broadcastSessionDestruction()).not.toThrow();

    const unavailable = createCrossTabSessionSync({ createChannel: () => null });
    expect(() => unavailable.broadcastSessionDestruction()).not.toThrow();
    expect(() => unavailable.dispose()).not.toThrow();
    expect(onRemoteDestruction).not.toHaveBeenCalled();
  });
});
