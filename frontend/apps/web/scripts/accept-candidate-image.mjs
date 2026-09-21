/**
 * Actual-image acceptance against a running municipal-on web candidate.
 * Env: CANDIDATE_BASE=http://127.0.0.1:PORT
 */
import { chromium } from 'playwright';

const base = process.env.CANDIDATE_BASE;
if (!base) {
  console.error('CANDIDATE_BASE required');
  process.exit(2);
}

const browser = await chromium.launch({
  // Candidate runs on localhost; live API CORS allowlist is app.parkio.dev.
  // Disable web security only for this local image-acceptance harness.
  args: ['--disable-web-security', '--disable-features=IsolateOrigins,site-per-process'],
});
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

await soft('anonymous /explore mounts', async () => {
  await page.goto(`${base}/explore`, { waitUntil: 'domcontentloaded', timeout: 60_000 });
  await page.waitForFunction(
    () => (document.getElementById('root')?.children.length ?? 0) > 0,
    null,
    { timeout: 30_000 },
  );
  await page.waitForSelector(
    '[data-testid="public-explore-product"], [data-testid="map-floating-locate"]',
    { timeout: 45_000 },
  );
});

await soft('markers preview AuthGate detail', async () => {
  await page.goto(`${base}/explore`, { waitUntil: 'networkidle', timeout: 90_000 });
  const marker = page.locator('[data-testid="municipal-facility-marker"]').first();
  await marker.waitFor({ timeout: 60_000 });
  await marker.click({ force: true, timeout: 10_000 });
  await page.waitForSelector('[data-testid="selected-municipal-facility-preview"]', {
    timeout: 20_000,
  });
  await page.getByTestId('municipal-facility-view-details').click();
  await page.waitForSelector('[data-testid="auth-gate-dialog"]', { timeout: 15_000 });
  const intent = await page.getByTestId('auth-gate-dialog').getAttribute('data-auth-gate-intent');
  if (intent !== 'facilityDetail') throw new Error(`intent=${intent}`);
});

await soft('login mounts', async () => {
  await page.goto(`${base}/login`, { waitUntil: 'networkidle', timeout: 45_000 });
  await page.waitForSelector('input', { timeout: 20_000 });
});

await soft('/map auth gate', async () => {
  await page.goto(`${base}/map`, { waitUntil: 'networkidle', timeout: 45_000 });
  const url = page.url();
  const hasLogin =
    /login/i.test(url) || (await page.locator('input[type="password"]').count()) > 0;
  if (!hasLogin) throw new Error(`url=${url}`);
});

await soft('admin waitlist shell', async () => {
  await page.goto(`${base}/admin/waitlist`, { waitUntil: 'networkidle', timeout: 45_000 });
  const kids = await page.evaluate(
    () => document.getElementById('root')?.children.length ?? 0,
  );
  if (kids < 1) throw new Error('white-screen');
});

await browser.close();
if (failures.length) {
  console.error('ACCEPTANCE_FAILED', failures.join(','));
  process.exit(1);
}
console.log('ACCEPTANCE_OK');
