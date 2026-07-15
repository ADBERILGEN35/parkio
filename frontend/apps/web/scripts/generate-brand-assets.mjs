/**
 * One-shot brand asset generator from the official Parkio logo raster.
 * Source: frontend/apps/web/public/brand/logo-son.png (white-bg master)
 * Output: transparent mark + platform icon sizes (web PWA + Expo/Android).
 */
import fs from 'node:fs';
import path from 'node:path';
import sharp from 'sharp';

const ROOT = 'C:/Users/ADBERILGEN/Documents/parkio/frontend';
const WEB_PUBLIC = path.join(ROOT, 'apps/web/public');
const BRAND_DIR = path.join(WEB_PUBLIC, 'brand');
const ICONS_DIR = path.join(WEB_PUBLIC, 'icons');
const MOBILE_ASSETS = path.join(ROOT, 'apps/mobile/assets/images');
const ANDROID_RES = path.join(ROOT, 'apps/mobile/android/app/src/main/res');

const SOURCE_CANDIDATES = [
  path.join(BRAND_DIR, 'parkio-logo.png'),
  path.join(BRAND_DIR, 'logo-son.png'),
];

const BRAND_BLUE = { r: 0, g: 80, b: 203 }; // #0050CB
const NAVY_BG = { r: 10, g: 37, b: 64 }; // #0A2540
const WHITE_THRESH = 248;

async function main() {
  const sourcePath = SOURCE_CANDIDATES.find((p) => fs.existsSync(p));
  if (!sourcePath) {
    throw new Error('No source logo found (parkio-logo.png or logo-son.png)');
  }

  fs.mkdirSync(BRAND_DIR, { recursive: true });
  fs.mkdirSync(ICONS_DIR, { recursive: true });
  fs.mkdirSync(MOBILE_ASSETS, { recursive: true });

  // Canonical unprocessed master copy
  const canonical = path.join(BRAND_DIR, 'parkio-logo.png');
  if (path.resolve(sourcePath) !== path.resolve(canonical)) {
    fs.copyFileSync(sourcePath, canonical);
  }

  const { data, info } = await sharp(canonical)
    .ensureAlpha()
    .raw()
    .toBuffer({ resolveWithObject: true });

  removeEdgeConnectedWhiteBackground(data, info.width, info.height);
  softenFringeAgainstTransparency(data, info.width, info.height);

  const transparent = await sharp(data, {
    raw: { width: info.width, height: info.height, channels: 4 },
  })
    .png()
    .toBuffer();

  // Trim transparent margins then re-add modest padding for UI mark
  const trimmed = await sharp(transparent).trim({ threshold: 10 }).png().toBuffer();
  const markMeta = await sharp(trimmed).metadata();
  const pad = Math.round(Math.max(markMeta.width, markMeta.height) * 0.06);
  const markPadded = await sharp(trimmed)
    .extend({
      top: pad,
      bottom: pad,
      left: pad,
      right: pad,
      background: { r: 0, g: 0, b: 0, alpha: 0 },
    })
    .png()
    .toBuffer();

  // Square transparent UI mark (preserve aspect via contain; no stretch)
  await sharp(markPadded)
    .resize(512, 512, {
      fit: 'contain',
      background: { r: 0, g: 0, b: 0, alpha: 0 },
    })
    .png()
    .toFile(path.join(BRAND_DIR, 'parkio-logo-mark.png'));

  await resizeContain(markPadded, 128, path.join(BRAND_DIR, 'parkio-logo-mark-128.png'));

  // Browser / PWA "any" icons — brand blue plate for crisp small sizes
  await iconOnSolid(markPadded, 32, BRAND_BLUE, 0.72, path.join(ICONS_DIR, 'favicon-32.png'));
  await iconOnSolid(markPadded, 180, BRAND_BLUE, 0.78, path.join(ICONS_DIR, 'apple-touch-icon.png'));
  await iconOnSolid(markPadded, 192, BRAND_BLUE, 0.78, path.join(ICONS_DIR, 'parkio-icon-192.png'));
  await iconOnSolid(markPadded, 512, BRAND_BLUE, 0.78, path.join(ICONS_DIR, 'parkio-icon-512.png'));

  // Maskable: safe-zone ~20% padding on navy (matches Android adaptive bg)
  await maskable(markPadded, 512, NAVY_BG, path.join(ICONS_DIR, 'parkio-maskable-512.png'));
  await maskable(markPadded, 512, NAVY_BG, path.join(ICONS_DIR, 'parkio-maskable.png'));

  // SVG wrappers pointing at PNG (keeps existing .svg manifest/favicon links valid)
  writeSvgImageProxy(
    path.join(ICONS_DIR, 'parkio-icon.svg'),
    '/icons/parkio-icon-512.png',
  );
  writeSvgImageProxy(
    path.join(ICONS_DIR, 'parkio-maskable.svg'),
    '/icons/parkio-maskable-512.png',
  );
  writeSvgImageProxy(
    path.join(ICONS_DIR, 'apple-touch-icon.svg'),
    '/icons/apple-touch-icon.png',
  );

  // Wide logo.svg lockup (mark + wordmark) for crawlers that expect logo.svg
  await writeLogoSvgLockup(markPadded, path.join(WEB_PUBLIC, 'logo.svg'));

  // Social: 1200×630 composition (do NOT stretch square into banner)
  await writeOg(markPadded, path.join(WEB_PUBLIC, 'og-parkio.png'));
  await writeOg(markPadded, path.join(WEB_PUBLIC, 'social-preview.png'));

  // Expo / React Native source icons
  await iconOnSolid(markPadded, 1024, NAVY_BG, 0.7, path.join(MOBILE_ASSETS, 'icon.png'));
  // Adaptive foreground: transparent with logo in center ~66% safe zone
  await adaptiveForeground(markPadded, 1024, path.join(MOBILE_ASSETS, 'adaptive-icon.png'));
  await iconOnSolid(markPadded, 512, NAVY_BG, 0.7, path.join(MOBILE_ASSETS, 'splash-icon.png'));
  await iconOnSolid(markPadded, 48, BRAND_BLUE, 0.78, path.join(MOBILE_ASSETS, 'favicon.png'));
  // Android status-bar glyphs must be white/alpha (Expo notifications plugin source).
  await notificationGlyph(markPadded, 96, path.join(MOBILE_ASSETS, 'notification-icon.png'));

  // Android mipmaps + splash / notification
  await generateAndroid(markPadded);

  console.log('Brand assets generated from', sourcePath);
  console.log('Source size:', info.width, 'x', info.height);
  console.log('Edge-connected white background removed; fringe softened');
}

/** Background white keyed only when reachable from image edges (preserves white car body). */
function removeEdgeConnectedWhiteBackground(data, width, height) {
  const isBg = (idx) => {
    const r = data[idx];
    const g = data[idx + 1];
    const b = data[idx + 2];
    return r >= WHITE_THRESH && g >= WHITE_THRESH && b >= WHITE_THRESH;
  };

  const visited = new Uint8Array(width * height);
  const queue = [];

  const push = (x, y) => {
    if (x < 0 || y < 0 || x >= width || y >= height) return;
    const p = y * width + x;
    if (visited[p]) return;
    const idx = p * 4;
    if (!isBg(idx)) return;
    visited[p] = 1;
    queue.push(p);
  };

  for (let x = 0; x < width; x++) {
    push(x, 0);
    push(x, height - 1);
  }
  for (let y = 0; y < height; y++) {
    push(0, y);
    push(width - 1, y);
  }

  while (queue.length) {
    const p = queue.pop();
    data[p * 4 + 3] = 0;
    const x = p % width;
    const y = (p / width) | 0;
    push(x + 1, y);
    push(x - 1, y);
    push(x, y + 1);
    push(x, y - 1);
  }
}

/** Soften only near-white pixels that touch already-transparent background. */
function softenFringeAgainstTransparency(data, width, height) {
  const copy = Buffer.from(data);
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const p = y * width + x;
      const idx = p * 4;
      if (copy[idx + 3] === 0) continue;
      const r = copy[idx];
      const g = copy[idx + 1];
      const b = copy[idx + 2];
      if (!(r > 235 && g > 235 && b > 235)) continue;
      let touchesClear = false;
      for (const [dx, dy] of [
        [1, 0],
        [-1, 0],
        [0, 1],
        [0, -1],
      ]) {
        const nx = x + dx;
        const ny = y + dy;
        if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue;
        if (copy[(ny * width + nx) * 4 + 3] === 0) {
          touchesClear = true;
          break;
        }
      }
      if (!touchesClear) continue;
      const hardness = Math.max(r, g, b);
      data[idx + 3] = Math.round(((255 - hardness) / (255 - 235)) * 180);
    }
  }
}

async function resizeContain(input, size, outPath) {
  await sharp(input)
    .resize(size, size, { fit: 'contain', background: { r: 0, g: 0, b: 0, alpha: 0 } })
    .png()
    .toFile(outPath);
}

async function iconOnSolid(mark, size, bg, scale, outPath) {
  const logoSize = Math.round(size * scale);
  const logo = await sharp(mark)
    .resize(logoSize, logoSize, {
      fit: 'contain',
      background: { r: 0, g: 0, b: 0, alpha: 0 },
    })
    .png()
    .toBuffer();
  const left = Math.round((size - logoSize) / 2);
  const top = Math.round((size - logoSize) / 2);
  await sharp({
    create: {
      width: size,
      height: size,
      channels: 4,
      background: { ...bg, alpha: 1 },
    },
  })
    .composite([{ input: logo, left, top }])
    .png()
    .toFile(outPath);
}

async function maskable(mark, size, bg, outPath) {
  // Keep logo inside ~66% of canvas for circular / rounded masks
  await iconOnSolid(mark, size, bg, 0.66, outPath);
}

async function adaptiveForeground(mark, size, outPath) {
  const logoSize = Math.round(size * 0.62);
  const logo = await sharp(mark)
    .resize(logoSize, logoSize, {
      fit: 'contain',
      background: { r: 0, g: 0, b: 0, alpha: 0 },
    })
    .png()
    .toBuffer();
  const left = Math.round((size - logoSize) / 2);
  const top = Math.round((size - logoSize) / 2);
  await sharp({
    create: {
      width: size,
      height: size,
      channels: 4,
      background: { r: 0, g: 0, b: 0, alpha: 0 },
    },
  })
    .composite([{ input: logo, left, top }])
    .png()
    .toFile(outPath);
}

function writeSvgImageProxy(filePath, href) {
  const svg = `<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 512 512" role="img" aria-label="Parkio">
  <image href="${href}" xlink:href="${href}" width="512" height="512" preserveAspectRatio="xMidYMid meet"/>
</svg>
`;
  fs.writeFileSync(filePath, svg, 'utf8');
}

async function writeLogoSvgLockup(mark, outPath) {
  const mark64 = (await sharp(mark).resize(128, 128, { fit: 'contain' }).png().toBuffer()).toString(
    'base64',
  );
  const svg = `<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="480" height="128" viewBox="0 0 480 128" role="img" aria-label="Parkio">
  <image href="data:image/png;base64,${mark64}" width="112" height="112" x="8" y="8" preserveAspectRatio="xMidYMid meet"/>
  <text x="136" y="82" font-family="Inter,Segoe UI,Arial,sans-serif" font-size="56" font-weight="700" fill="#0050CB">Parkio</text>
</svg>
`;
  fs.writeFileSync(outPath, svg, 'utf8');
}

async function writeOg(mark, outPath) {
  const logo = await sharp(mark)
    .resize(280, 280, { fit: 'contain', background: { r: 0, g: 0, b: 0, alpha: 0 } })
    .png()
    .toBuffer();
  const wordmark = Buffer.from(`
    <svg width="420" height="120" xmlns="http://www.w3.org/2000/svg">
      <text x="0" y="84" font-family="Inter,Segoe UI,Arial,sans-serif" font-size="84" font-weight="700" fill="#FFFFFF">Parkio</text>
    </svg>
  `);
  const wordBuf = await sharp(wordmark).png().toBuffer();
  await sharp({
    create: {
      width: 1200,
      height: 630,
      channels: 3,
      background: NAVY_BG,
    },
  })
    .composite([
      { input: logo, left: 260, top: 175 },
      { input: wordBuf, left: 560, top: 255 },
    ])
    .png()
    .toFile(outPath);
}

async function generateAndroid(mark) {
  const launcher = {
    mdpi: 48,
    hdpi: 72,
    xhdpi: 96,
    xxhdpi: 144,
    xxxhdpi: 192,
  };
  const foreground = {
    mdpi: 108,
    hdpi: 162,
    xhdpi: 216,
    xxhdpi: 324,
    xxxhdpi: 432,
  };
  const splash = {
    mdpi: 160,
    hdpi: 240,
    xhdpi: 320,
    xxhdpi: 480,
    xxxhdpi: 640,
  };
  const notification = {
    mdpi: 24,
    hdpi: 36,
    xhdpi: 48,
    xxhdpi: 72,
    xxxhdpi: 96,
  };

  for (const [density, size] of Object.entries(launcher)) {
    const dir = path.join(ANDROID_RES, `mipmap-${density}`);
    fs.mkdirSync(dir, { recursive: true });
    const plate = await solidIconBuffer(mark, size, NAVY_BG, 0.7);
    await sharp(plate).webp({ quality: 90 }).toFile(path.join(dir, 'ic_launcher.webp'));
    await sharp(plate).webp({ quality: 90 }).toFile(path.join(dir, 'ic_launcher_round.webp'));
  }

  for (const [density, size] of Object.entries(foreground)) {
    const dir = path.join(ANDROID_RES, `mipmap-${density}`);
    fs.mkdirSync(dir, { recursive: true });
    const fg = await adaptiveFgBuffer(mark, size);
    await sharp(fg).webp({ quality: 90 }).toFile(path.join(dir, 'ic_launcher_foreground.webp'));
  }

  for (const [density, size] of Object.entries(splash)) {
    const dir = path.join(ANDROID_RES, `drawable-${density}`);
    fs.mkdirSync(dir, { recursive: true });
    await iconOnSolid(mark, size, NAVY_BG, 0.7, path.join(dir, 'splashscreen_logo.png'));
  }

  // Notification icons should be white/alpha silhouette on Android — use
  // a high-contrast alpha of the mark (platform tints with notification color).
  for (const [density, size] of Object.entries(notification)) {
    const dir = path.join(ANDROID_RES, `drawable-${density}`);
    fs.mkdirSync(dir, { recursive: true });
    await notificationGlyph(mark, size, path.join(dir, 'notification_icon.png'));
  }
}

async function solidIconBuffer(mark, size, bg, scale) {
  const logoSize = Math.round(size * scale);
  const logo = await sharp(mark)
    .resize(logoSize, logoSize, {
      fit: 'contain',
      background: { r: 0, g: 0, b: 0, alpha: 0 },
    })
    .png()
    .toBuffer();
  const left = Math.round((size - logoSize) / 2);
  const top = Math.round((size - logoSize) / 2);
  return sharp({
    create: {
      width: size,
      height: size,
      channels: 4,
      background: { ...bg, alpha: 1 },
    },
  })
    .composite([{ input: logo, left, top }])
    .png()
    .toBuffer();
}

async function adaptiveFgBuffer(mark, size) {
  const logoSize = Math.round(size * 0.62);
  const logo = await sharp(mark)
    .resize(logoSize, logoSize, {
      fit: 'contain',
      background: { r: 0, g: 0, b: 0, alpha: 0 },
    })
    .png()
    .toBuffer();
  const left = Math.round((size - logoSize) / 2);
  const top = Math.round((size - logoSize) / 2);
  return sharp({
    create: {
      width: size,
      height: size,
      channels: 4,
      background: { r: 0, g: 0, b: 0, alpha: 0 },
    },
  })
    .composite([{ input: logo, left, top }])
    .png()
    .toBuffer();
}

async function notificationGlyph(mark, size, outPath) {
  // Flatten to white silhouette using alpha channel of the mark
  const logo = await sharp(mark)
    .resize(size, size, { fit: 'contain', background: { r: 0, g: 0, b: 0, alpha: 0 } })
    .ensureAlpha()
    .raw()
    .toBuffer({ resolveWithObject: true });

  const out = Buffer.alloc(logo.data.length);
  for (let i = 0; i < logo.data.length; i += 4) {
    const a = logo.data[i + 3];
    out[i] = 255;
    out[i + 1] = 255;
    out[i + 2] = 255;
    out[i + 3] = a;
  }

  await sharp(out, {
    raw: { width: logo.info.width, height: logo.info.height, channels: 4 },
  })
    .png()
    .toFile(outPath);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
