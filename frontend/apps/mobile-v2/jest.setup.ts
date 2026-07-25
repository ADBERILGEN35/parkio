/* eslint-disable @typescript-eslint/no-require-imports */
// @testing-library/react-native v13 ships its jest matchers built in — this
// file hosts native-module mocks and async-utility timing only.

import { configure } from '@testing-library/react-native';

// RNTL defaults `waitFor` to 1s. On shared CI runners several workspace suites
// render concurrently, and React Native renders here regularly need longer than
// that — which surfaced as "element is still mounted" failures that never
// reproduce in isolation. Only the polling budget changes; every assertion and
// its final outcome stay identical.
configure({ asyncUtilTimeout: 15_000 });

// expo-secure-store hits the native keystore — replace with an in-memory map.
jest.mock('expo-secure-store', () => {
  const store = new Map<string, string>();
  return {
    getItemAsync: jest.fn(async (key: string) => store.get(key) ?? null),
    setItemAsync: jest.fn(async (key: string, value: string) => {
      store.set(key, value);
    }),
    deleteItemAsync: jest.fn(async (key: string) => {
      store.delete(key);
    }),
  };
});

jest.mock('@react-native-community/netinfo', () => ({
  addEventListener: jest.fn(() => jest.fn()),
  fetch: jest.fn(async () => ({ isConnected: true, isInternetReachable: true })),
}));

// expo-file-system File/Directory classes touch the native FS — in-memory stub.
jest.mock('expo-file-system', () => {
  const files = new Map<string, string>();
  class File {
    private key: string;
    constructor(...parts: string[]) {
      this.key = parts.join('/');
    }
    get exists(): boolean {
      return files.has(this.key);
    }
    get uri(): string {
      return this.key;
    }
    textSync(): string {
      const value = files.get(this.key);
      if (value === undefined) throw new Error('ENOENT');
      return value;
    }
    write(content: string): void {
      files.set(this.key, content);
    }
    delete(): void {
      files.delete(this.key);
    }
    copy(): void {}
  }
  class Directory {
    get exists(): boolean {
      return true;
    }
    create(): void {}
  }
  return { File, Directory, Paths: { document: '/doc', cache: '/cache' } };
});

// @expo/vector-icons loads its font asynchronously inside the Icon component;
// that setState lands after teardown and produces act() noise. Replace with a
// synchronous Text stub (icon name as content) so icons stay assertable.
jest.mock('@expo/vector-icons', () => {
  const React = require('react');
  const { Text } = require('react-native');
  const makeIcon = () => {
    const Icon = ({ name, ...props }: { name: string }) =>
      React.createElement(Text, props, String(name));
    (Icon as unknown as { glyphMap: Record<string, number> }).glyphMap = new Proxy(
      {},
      { get: () => 1, has: () => true },
    ) as Record<string, number>;
    return Icon;
  };
  return {
    MaterialCommunityIcons: makeIcon(),
    MaterialIcons: makeIcon(),
    Ionicons: makeIcon(),
  };
});

// Reanimated: the official mock still pulls the native initializers under
// pnpm, so provide a minimal hand-rolled mock. `useReducedMotion` returns true
// so components take their static (reduced-motion) branches in tests.
jest.mock('react-native-reanimated', () => {
  const RN = require('react-native');
  const chain: Record<string, unknown> = {};
  for (const method of ['duration', 'delay', 'springify', 'damping', 'stiffness', 'easing']) {
    chain[method] = () => chain;
  }
  const presets = [
    'FadeIn',
    'FadeOut',
    'FadeInUp',
    'FadeOutUp',
    'FadeInDown',
    'FadeOutDown',
    'FadeInRight',
    'FadeOutLeft',
    'SlideInDown',
    'SlideOutDown',
    'ZoomIn',
    'LinearTransition',
  ].reduce<Record<string, unknown>>((acc, name) => {
    acc[name] = chain;
    return acc;
  }, {});
  const passthrough = (value: unknown) => value;
  const Animated = {
    View: RN.View,
    Text: RN.Text,
    ScrollView: RN.ScrollView,
    createAnimatedComponent: (Component: unknown) => Component,
  };
  return {
    __esModule: true,
    default: Animated,
    ...Animated,
    ...presets,
    useSharedValue: (value: unknown) => ({ value }),
    useAnimatedStyle: () => ({}),
    useDerivedValue: (factory: () => unknown) => ({ value: factory() }),
    withTiming: passthrough,
    withSpring: passthrough,
    withDelay: (_delay: number, value: unknown) => value,
    withRepeat: passthrough,
    interpolate: () => 0,
    interpolateColor: () => '#000000',
    Easing: new Proxy({}, { get: () => (input: unknown) => input }),
    useReducedMotion: () => true,
    cancelAnimation: () => {},
    runOnJS: (fn: (...args: unknown[]) => unknown) => fn,
    runOnUI: (fn: (...args: unknown[]) => unknown) => fn,
  };
});

// Pure-View stand-ins for visual-effect natives.
jest.mock('expo-blur', () => {
  const React = require('react');
  const { View } = require('react-native');
  return { BlurView: (props: object) => React.createElement(View, props) };
});
jest.mock('expo-linear-gradient', () => {
  const React = require('react');
  const { View } = require('react-native');
  return { LinearGradient: (props: object) => React.createElement(View, props) };
});
