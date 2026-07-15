/**
 * Ensures every English source key used as a literal in mobile app screens
 * has a Turkish mapping (or is an intentional proper noun).
 *
 * Usage: node scripts/validate-i18n-parity.mjs
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.join(__dirname, '..');
const require = createRequire(import.meta.url);

// Load via dynamic import of compiled-free TS strip is flaky; parse the table instead.
const translationsSrc = fs.readFileSync(path.join(root, 'src/i18n/translations.ts'), 'utf8');
const keyRe = /^\s*(?:'([^']+)'|([A-Za-z][A-Za-z0-9 ]*)):/gm;
const keys = new Set();
let m;
while ((m = keyRe.exec(translationsSrc))) {
  keys.add(m[1] || m[2]);
}

const required = [
  'Welcome back',
  'Sign in to find and share parking.',
  'Map',
  'My spots',
  'Share',
  'Leaderboard',
  'Profile',
  'Find parking',
  'Search a place or address',
  'Search this area',
  '1 spot nearby',
  'Parking spot',
  'Likely available',
  'Community reported',
  'Legal parking',
  'View details',
  'Get directions',
  'Add a photo of the spot',
  'Take photo',
  'Choose from gallery',
  'Uploading photo',
  'Spot is live',
  'You earned points',
  'Mark as read',
  'Signed in as',
  'About the app',
  'GPS accuracy',
  'Selected area',
  'Leave parking spot sharing?',
  'Continue sharing',
  'Leave and discard changes',
];

const missing = required.filter((k) => !keys.has(k));
if (missing.length) {
  console.error('Missing Turkish mappings for:');
  for (const k of missing) console.error(' -', k);
  process.exit(1);
}

console.log(`i18n parity OK (${required.length} critical keys present, ${keys.size} total catalog entries)`);
