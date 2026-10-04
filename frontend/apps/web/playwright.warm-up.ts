import { chromium, type FullConfig } from '@playwright/test';

// Routes whose modules the first tests load. Unauthenticated visits are enough: the guarded pages
// redirect, and API calls go unanswered by the dev server, but the modules still get compiled.
const WARM_PATHS = ['/', '/explore', '/login', '/register', '/map'];

/**
 * Global setup: loads the app's main routes once on the freshly started Vite dev server, so the
 * first test does not pay for its cold start. That start means on-demand transforms and the
 * dependency optimizer's full-page reload. Without the warm-up, the first test of a run could time out
 * waiting for the app to boot. Best effort: a route that still fails here fails in its own test.
 */
export default async function warmUp(config: FullConfig): Promise<void> {
  const baseURL = config.projects[0]?.use.baseURL;
  if (!baseURL) return;
  const browser = await chromium.launch();
  try {
    const page = await browser.newPage();
    for (const path of WARM_PATHS) {
      // The optimizer's reload can interrupt the first attempt; the second finds the server warm.
      for (let attempt = 0; attempt < 2; attempt += 1) {
        try {
          await page.goto(new URL(path, baseURL).toString(), { waitUntil: 'load', timeout: 120_000 });
          await page.waitForFunction(
            () => (document.getElementById('root')?.childElementCount ?? 0) > 0,
            undefined,
            { timeout: 60_000 },
          );
          break;
        } catch {
          // Retried once, then left to the tests.
        }
      }
    }
  } finally {
    await browser.close();
  }
}
