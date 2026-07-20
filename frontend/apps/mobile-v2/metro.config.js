/**
 * Metro config tuned for the pnpm monorepo.
 *
 * Metro must (1) watch the repo root so changes in `packages/*` hot-reload, and
 * (2) resolve modules from both the app's own `node_modules` and the hoisted
 * root store. `disableHierarchicalLookup` is left off so pnpm's nested,
 * symlinked layout resolves correctly. The shared workspace packages
 * (`@parkio/*`) ship raw TypeScript, so they are transpiled by Metro like app
 * source — no prebuild step required.
 */
const { getDefaultConfig } = require('expo/metro-config');
const path = require('path');

const projectRoot = __dirname;
const monorepoRoot = path.resolve(projectRoot, '../..');

const config = getDefaultConfig(projectRoot);

config.watchFolders = [monorepoRoot];
config.resolver.nodeModulesPaths = [
  path.resolve(projectRoot, 'node_modules'),
  path.resolve(monorepoRoot, 'node_modules'),
];
config.resolver.disableHierarchicalLookup = false;

module.exports = config;
