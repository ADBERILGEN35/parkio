const { defineConfig } = require('eslint/config');
const expoConfig = require('eslint-config-expo/flat');

module.exports = defineConfig([
  expoConfig,
  {
    ignores: [
      'dist/*',
      'node_modules/*',
      '.expo/*',
      'scripts/*',
      // Generated third-party MapLibre runtime (PA-05) — do not lint vendor UMD.
      'src/features/map/vendor/**',
    ],
  },
]);
