/**
 * Jest config using the `jest-expo` preset (RN transformer, module mappers, Expo
 * globals).
 *
 * pnpm note: dependencies live under `node_modules/.pnpm/<pkg>@<ver>/node_modules/...`,
 * which defeats the default `transformIgnorePatterns`. We therefore target the
 * `.pnpm` layout directly and whitelist the React Native / Expo ESM packages (and
 * their scoped variants) so Babel transforms them instead of Node choking on
 * `import` statements. Workspace `@parkio/*` packages live outside node_modules
 * and are transformed by default.
 */
const transformAllowList = [
  '@?react-native',
  '@react-navigation',
  'expo',
  '@expo',
  '@expo-google-fonts',
  '@unimodules',
  'unimodules',
  '@react-native-community',
].join('|');

module.exports = {
  preset: 'jest-expo',
  setupFilesAfterEnv: ['<rootDir>/jest.setup.ts'],
  moduleNameMapper: {
    '^@/(.*)$': '<rootDir>/src/$1',
  },
  collectCoverageFrom: ['src/**/*.{ts,tsx}', 'app/**/*.{ts,tsx}'],
  transformIgnorePatterns: [`node_modules/.pnpm/(?!(${transformAllowList}))`],
};
