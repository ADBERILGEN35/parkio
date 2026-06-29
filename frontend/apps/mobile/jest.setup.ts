/* global jest */
// @testing-library/react-native (v12.4+) registers its Jest matchers automatically
// via the jest-expo preset, so no `extend-expect` import is required here.

// expo-secure-store is a native module; back it with an in-memory map in tests so
// token-storage logic is exercised without a device keystore.
jest.mock('expo-secure-store', () => {
  const store = new Map<string, string>();
  return {
    setItemAsync: jest.fn(async (key: string, value: string) => {
      store.set(key, value);
    }),
    getItemAsync: jest.fn(async (key: string) => store.get(key) ?? null),
    deleteItemAsync: jest.fn(async (key: string) => {
      store.delete(key);
    }),
    __resetStore: () => store.clear(),
  };
});

// NetInfo native module — default to "online" in tests.
jest.mock('@react-native-community/netinfo', () => ({
  addEventListener: jest.fn(() => jest.fn()),
  fetch: jest.fn(async () => ({ isConnected: true, isInternetReachable: true })),
}));
