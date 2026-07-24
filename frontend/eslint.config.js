import baseConfig from './packages/config/eslint.config.js';

export default [
  ...baseConfig,
  {
    files: ['scripts/architecture/**/*.mjs', 'scripts/contracts/**/*.mjs'],
    languageOptions: {
      globals: {
        console: 'readonly',
        process: 'readonly',
      },
    },
  },
];
