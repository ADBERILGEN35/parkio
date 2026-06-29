// Flat ESLint config (ESLint 9) built on Expo's shared config, which already
// wires the React, React Hooks, React Native and import rules Expo recommends.
const expoConfig = require('eslint-config-expo/flat');

module.exports = [
  ...expoConfig,
  {
    ignores: [
      'dist/*',
      '.expo/*',
      'node_modules/*',
      'coverage/*',
      'babel.config.js',
      'metro.config.js',
    ],
  },
];
