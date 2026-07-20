/**
 * Jest config using the `jest-expo` preset (RN transformer, module mappers,
 * Expo globals).
 *
 * pnpm note: dependencies live under `node_modules/.pnpm/<pkg>@<ver>/node_modules/...`,
 * which defeats the default `transformIgnorePatterns`. Target the `.pnpm`
 * layout directly and allow the React Native / Expo ESM packages (and their
 * scoped variants) so Babel transforms them instead of Node choking on
 * `import` statements. Workspace `@parkio/*` packages live outside
 * node_modules and are transformed by default.
 */
const transformAllowList = [
  '@?react-native',
  '@react-navigation',
  'expo',
  '@expo',
  '@expo-google-fonts',
  '@gorhom',
  '@react-native-community',
].join('|');

module.exports = {
  preset: 'jest-expo',
  setupFiles: ['<rootDir>/jest.env.js'],
  setupFilesAfterEnv: ['<rootDir>/jest.setup.ts'],
  testMatch: ['**/*.test.{ts,tsx}'],
  moduleNameMapper: {
    '^@/(.*)$': '<rootDir>/src/$1',
  },
  testPathIgnorePatterns: ['/node_modules/', '/.expo/'],
  transformIgnorePatterns: [`node_modules/.pnpm/(?!(${transformAllowList}))`],
};
