const { chromium } = require('@playwright/test');
const fs = require('node:fs/promises');
const path = require('node:path');

const publicDir = path.resolve(__dirname, '../public');

async function renderSvg(svgPath, outPath, width, height) {
  const svg = await fs.readFile(path.join(publicDir, svgPath), 'utf8');
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width, height }, deviceScaleFactor: 1 });

  await page.setContent(`<!doctype html>
    <html>
      <body style="margin:0;background:transparent;display:grid;place-items:center;width:${width}px;height:${height}px">
        ${svg}
      </body>
    </html>`);

  const locator = page.locator('svg');
  await locator.evaluate(
    (node, size) => {
      node.setAttribute('width', String(size.width));
      node.setAttribute('height', String(size.height));
      node.style.display = 'block';
    },
    { width, height },
  );
  await locator.screenshot({ path: path.join(publicDir, outPath), omitBackground: true });
  await browser.close();
}

async function main() {
  await fs.mkdir(path.join(publicDir, 'icons'), { recursive: true });
  await renderSvg('icons/parkio-icon.svg', 'icons/favicon-32.png', 32, 32);
  await renderSvg('icons/apple-touch-icon.svg', 'icons/apple-touch-icon.png', 180, 180);
  await renderSvg('icons/parkio-icon.svg', 'icons/parkio-icon-192.png', 192, 192);
  await renderSvg('icons/parkio-icon.svg', 'icons/parkio-icon-512.png', 512, 512);
  await renderSvg('icons/parkio-maskable.svg', 'icons/parkio-maskable-512.png', 512, 512);
  await renderSvg('og-parkio.svg', 'og-parkio.png', 1200, 630);
  await fs.copyFile(path.join(publicDir, 'og-parkio.png'), path.join(publicDir, 'social-preview.png'));
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
