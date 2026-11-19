#!/usr/bin/env bash
#
# Actual-image acceptance for a municipal-on (or explore-on) web candidate.
# Does NOT use MapPage unit/MSW fixtures. Serves the built image and probes UI + live API.
#
# Usage:
#   ./scripts/accept-web-candidate-image.sh parkio/web:municipal-on-candidate [port]
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

IMAGE="${1:?image ref required}"
PORT="${2:-18081}"
NAME="parkio-web-candidate-accept-$$"
API="https://api.parkio.dev/api/v1"
BASE="http://127.0.0.1:${PORT}"

cleanup() { docker rm -f "$NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT

echo "=== start candidate $IMAGE on :$PORT ==="
docker run -d --name "$NAME" -p "127.0.0.1:${PORT}:80" "$IMAGE" >/dev/null

for i in $(seq 1 30); do
  if curl -fsS "$BASE/" >/dev/null 2>&1; then break; fi
  sleep 1
done
curl -fsS "$BASE/" >/dev/null

echo "=== compiled bundle contract ==="
cid="$(docker create "$IMAGE")"
tmp="$(mktemp -d)"
docker cp "$cid:/usr/share/nginx/html" "$tmp/dist"
docker rm -f "$cid" >/dev/null
node frontend/apps/web/scripts/verify-bundle-env.mjs \
  --dist "$tmp/dist" \
  --app-env hosted-beta \
  --require-public-explore true \
  --require-municipal true
rm -rf "$tmp"

echo "=== smoke-image (SPA mount + served env) ==="
node frontend/apps/web/scripts/smoke-image.mjs --image "$IMAGE" --app-env hosted-beta --port "$((PORT + 1))"

echo "=== live API probes (Explore + registration + waitlist) ==="
curl -fsS "$API/auth/registration-mode" | tee /tmp/parkio-regmode.json
echo
curl -fsS -o /tmp/parkio-explore.json -w "explore_http=%{http_code}\n" \
  "$API/public/explore/facilities?lat=38.4237&lng=27.1428&radiusMeters=5000&limit=6"
node --input-type=module <<'JS'
import { readFileSync } from 'node:fs';
const body = JSON.parse(readFileSync('/tmp/parkio-explore.json', 'utf8'));
const facilities = body.facilities || body.items || body;
const count = Array.isArray(facilities) ? facilities.length : (facilities?.length ?? 0);
const pa06 = body.communitySpotCountInScope;
console.log(`explore_facilities=${count}`);
console.log(`pa06_communitySpotCountInScope=${pa06 === null || pa06 === undefined ? 'null' : pa06}`);
if (!count || count < 1) process.exit(2);
if (pa06 != null) {
  console.error('PA-06 regression: communitySpotCountInScope must be null on public explore');
  process.exit(3);
}
JS

# Admin waitlist shell path should still answer without mutating.
curl -fsS -o /tmp/parkio-waitlist-options.txt -w "waitlist_options_http=%{http_code}\n" \
  -X OPTIONS "$API/admin/waitlist/entries" || true

echo "=== playwright journeys against candidate SPA ==="
node --input-type=module <<JS
import { chromium } from 'playwright';

const base = '$BASE';
const browser = await chromium.launch();
const page = await browser.newPage();
const failures = [];

async function soft(name, fn) {
  try {
    await fn();
    console.log('PASS', name);
  } catch (error) {
    console.error('FAIL', name, error.message || error);
    failures.push(name);
  }
}

await soft('anonymous /explore mounts product map', async () => {
  await page.goto(base + '/explore', { waitUntil: 'networkidle', timeout: 60_000 });
  await page.waitForSelector('[data-testid="public-explore-product"], [data-testid="map-floating-locate"]', {
    timeout: 45_000,
  });
});

await soft('anonymous Explore markers + preview + AuthGate detail', async () => {
  await page.goto(base + '/explore', { waitUntil: 'networkidle', timeout: 60_000 });
  // Wait for municipal markers / list buttons
  const marker = page.locator('button').filter({ hasText: /otopark|Otopark|parking|Parking/i }).first();
  await marker.waitFor({ timeout: 60_000 });
  await marker.click();
  await page.waitForSelector('[data-testid="selected-municipal-facility-preview"]', { timeout: 20_000 });
  const details = page.getByTestId('municipal-facility-view-details');
  await details.click();
  await page.waitForSelector('[data-testid="auth-gate-dialog"]', { timeout: 15_000 });
  const intent = await page.getByTestId('auth-gate-dialog').getAttribute('data-auth-gate-intent');
  if (intent !== 'facilityDetail') throw new Error('expected facilityDetail AuthGate, got ' + intent);
  // Close / return
  const close = page.locator('[data-testid="auth-gate-dialog"] button').first();
  if (await close.count()) await close.click().catch(() => undefined);
});

await soft('login page mounts', async () => {
  await page.goto(base + '/login', { waitUntil: 'networkidle', timeout: 45_000 });
  await page.waitForSelector('input[type="email"], input[name="email"], input[type="text"]', {
    timeout: 20_000,
  });
});

await soft('/map requires auth (redirect or login gate)', async () => {
  await page.goto(base + '/map', { waitUntil: 'networkidle', timeout: 45_000 });
  const url = page.url();
  const hasLogin = /login/i.test(url) || (await page.locator('input[type="password"]').count()) > 0;
  if (!hasLogin) throw new Error('expected auth redirect/login for /map, url=' + url);
});

await soft('admin waitlist path remains reachable shell', async () => {
  await page.goto(base + '/admin/waitlist', { waitUntil: 'networkidle', timeout: 45_000 });
  // Unauthenticated admin should gate to login, not white-screen.
  const rootKids = await page.evaluate(() => document.getElementById('root')?.children.length ?? 0);
  if (rootKids < 1) throw new Error('admin waitlist white-screen');
});

await browser.close();
if (failures.length) {
  console.error('ACCEPTANCE_FAILED', failures.join(','));
  process.exit(1);
}
console.log('ACCEPTANCE_OK');
JS

echo "=== candidate acceptance complete ==="
