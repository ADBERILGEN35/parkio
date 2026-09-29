#!/usr/bin/env node

import { createHash } from 'node:crypto';
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, extname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDir = dirname(fileURLToPath(import.meta.url));
const root = resolve(scriptDir, '..', 'web', 'marketing');
const failures = [];

function check(condition, message) {
  if (!condition) failures.push(message);
}

function read(relative) {
  const path = join(root, relative);
  check(existsSync(path), `Missing required file: ${relative}`);
  return existsSync(path) ? readFileSync(path, 'utf8') : '';
}

function sha256(relative) {
  return createHash('sha256').update(readFileSync(join(root, relative))).digest('hex');
}

function walk(directory) {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = join(directory, entry.name);
    return entry.isDirectory() ? walk(path) : [path];
  });
}

const requiredFiles = [
  'index.html',
  'styles.css',
  'i18n.js',
  'waitlist.js',
  'privacy/index.html',
  'terms/index.html',
  'waitlist/confirm/index.html',
  'waitlist/unsubscribe/index.html',
  'robots.txt',
  'sitemap.xml',
  '404.html',
  '.htaccess',
  'site.webmanifest',
  'assets/favicon-32.png',
  'assets/favicon-180.png',
  'assets/favicon-512.png',
  'assets/parkio-logo.png',
  'assets/social-preview.png',
];
requiredFiles.forEach((relative) => check(existsSync(join(root, relative)), `Missing required file: ${relative}`));

const index = read('index.html');
const notFound = read('404.html');
const manifest = read('site.webmanifest');
const privacy = read('privacy/index.html');
const publicCopy = `${index}\n${notFound}\n${manifest}\n${privacy}`;

check((index.match(/<h1(?:\s|>)/gi) ?? []).length === 1, 'Marketing index must contain exactly one h1.');
for (const semanticTag of ['nav', 'main', 'footer']) {
  check(new RegExp(`<${semanticTag}(?:\\s|>)`, 'i').test(index), `Marketing index must contain semantic <${semanticTag}>.`);
}

check(/lang=["']tr["']/i.test(index), 'Fresh-visit default document language must be Turkish (lang=tr).');
check(index.includes('data-lang-option="tr"') && index.includes('data-lang-option="en"'), 'Language switcher buttons missing.');
check(index.includes('parkio.marketing.locale') || read('i18n.js').includes('parkio.marketing.locale'), 'Locale persistence key missing.');
check(index.includes('id="waitlist-form"'), 'Waitlist form missing.');
check(index.includes('id="waitlist-email"') && index.includes('for="waitlist-email"'), 'Waitlist email label/input missing.');
check(index.includes('id="waitlist-consent"'), 'Waitlist consent checkbox missing.');
check(index.includes('aria-live="polite"'), 'Waitlist feedback live region missing.');
check(privacy.includes('Registration notification list') || privacy.includes('bildirim listesi'), 'Privacy must describe waitlist.');
check(privacy.includes('info@parkio.dev'), 'Privacy must include contact for deletion.');

const exactContent = [
  '<link rel="canonical" href="https://parkio.dev/">',
  '<meta property="og:url" content="https://parkio.dev/">',
  '<meta property="og:image" content="https://parkio.dev/assets/social-preview.png">',
  '<meta name="twitter:image" content="https://parkio.dev/assets/social-preview.png">',
  'Oğuzhan Taşyaran',
  'mailto:info@parkio.dev',
  'href="/privacy/"',
  'href="/terms/"',
  'https://www.linkedin.com/in/oguzhan-tasyaran/',
  'https://www.linkedin.com/company/parkio-app',
  'https://app.parkio.dev/explore',
  'id="waitlist"',
  'Park alanı keşfet',
];
exactContent.forEach((value) => check(index.includes(value), `Required marketing content missing: ${value}`));

for (const anchor of ['#product', '#how', '#trust', '#business', '#roadmap', '#about', '#waitlist']) {
  check(index.includes(`href="${anchor}"`), `Primary navigation target missing: ${anchor}`);
  check(index.includes(`id="${anchor.slice(1)}"`), `Section id missing: ${anchor}`);
}

for (const ctaId of ['header-product-cta', 'primary-product-cta', 'today-product-cta']) {
  const pattern = new RegExp(
    `<a[^>]*id="${ctaId}"[^>]*href="https://app\\.parkio\\.dev/explore"[^>]*>[\\s\\S]*?Park alanı keşfet`,
    'i',
  );
  check(pattern.test(index), `Post-enable Explore CTA contract failed for ${ctaId}.`);
}
check(
  /href="https:\/\/app\.parkio\.dev\/"[^>]*>[\s\S]*?Giriş yap/i.test(index),
  'Secondary Sign in link to app.parkio.dev is required.',
);
check(!/Pre-enable product state/i.test(index), 'Pre-enable product state copy must be removed.');
check(!/pending final enablement/i.test(index), 'Pending enablement copy must be removed.');
check(!/Public access is prepared and remains disabled/i.test(index), 'Disabled public-access copy must be removed.');

const bannedSignals = [
  /\billustrative\b/i,
  /\bmock(?:up)?s?\b/i,
  /\bprototype\b/i,
  /\bplaceholder\b/i,
  /\bcoming soon\b/i,
  /\bunder construction\b/i,
  /\brequest (?:a )?demo\b/i,
  /\bcreate account\b/i,
  /\bsign up now\b/i,
  /\bget started free\b/i,
  /\btrusted by\b/i,
  /\bdrivers across\b/i,
  /\bthousands of\b/i,
  /\bfive[- ]star\b/i,
  /\btestimonial(?:s)?\b/i,
  /\bmunicipal partner(?:ship)?\b/i,
  /\breal[- ]time parking availability\b/i,
];
bannedSignals.forEach((pattern) => check(!pattern.test(publicCopy), `Unsupported public signal found: ${pattern}`));

check(!/href\s*=\s*["'](?:|#)["']/i.test(index), 'Empty or fragment-only href found.');
check(
  (index.match(/https:\/\/www\.linkedin\.com\/in\/oguzhan-tasyaran\//g) ?? []).length >= 2,
  'Founder LinkedIn must appear in visible HTML and structured data.',
);
check(
  (index.match(/https:\/\/www\.linkedin\.com\/company\/parkio-app/g) ?? []).length >= 2,
  'Company LinkedIn must appear in visible HTML and Organization sameAs.',
);

const jsonLdMatch = index.match(/<script type="application\/ld\+json">([\s\S]*?)<\/script>/i);
check(Boolean(jsonLdMatch), 'Organization JSON-LD is missing.');
if (jsonLdMatch) {
  try {
    const graph = JSON.parse(jsonLdMatch[1])['@graph'];
    const organization = graph.find((entry) => entry['@type'] === 'Organization');
    const webApp = graph.find((entry) => entry['@type'] === 'WebApplication');
    check(organization?.founder?.name === 'Oğuzhan Taşyaran', 'JSON-LD founder name is incorrect.');
    check(organization?.founder?.jobTitle === 'Founder', 'JSON-LD founder jobTitle is incorrect.');
    const founderSameAs = [].concat(organization?.founder?.sameAs ?? []);
    check(
      founderSameAs.includes('https://www.linkedin.com/in/oguzhan-tasyaran/'),
      'JSON-LD founder.sameAs must include the founder LinkedIn URL.',
    );
    check(
      !founderSameAs.includes('https://www.linkedin.com/company/parkio-app'),
      'Company LinkedIn must not appear in founder.sameAs.',
    );
    const orgSameAs = [].concat(organization?.sameAs ?? []);
    check(
      orgSameAs.includes('https://www.linkedin.com/company/parkio-app'),
      'JSON-LD Organization.sameAs must include the company LinkedIn URL.',
    );
    check(webApp?.url === 'https://app.parkio.dev/explore', 'JSON-LD WebApplication.url must be Explore.');
  } catch (error) {
    failures.push(`Invalid JSON-LD: ${error.message}`);
  }
}

const htmlFiles = walk(root).filter((path) => extname(path) === '.html');
for (const htmlPath of htmlFiles) {
  const html = readFileSync(htmlPath, 'utf8');
  const attributes = [...html.matchAll(/\b(?:href|src)=["']([^"']+)["']/gi)].map((match) => match[1]);
  const ids = new Set([...html.matchAll(/\bid=["']([^"']+)["']/gi)].map((match) => match[1]));

  for (const value of attributes) {
    if (/^(?:https?:|mailto:|tel:|data:)/i.test(value)) continue;
    if (value.startsWith('#')) {
      check(ids.has(value.slice(1)), `Broken fragment ${value} in ${htmlPath.slice(root.length + 1)}`);
      continue;
    }

    const clean = value.split(/[?#]/, 1)[0];
    const target = clean.startsWith('/') ? join(root, clean) : resolve(dirname(htmlPath), clean);
    const resolvedTarget = clean.endsWith('/') ? join(target, 'index.html') : target;
    check(existsSync(resolvedTarget) && statSync(resolvedTarget).isFile(), `Broken local reference ${value} in ${htmlPath.slice(root.length + 1)}`);
  }
}

const social = readFileSync(join(root, 'assets', 'social-preview.png'));
check(social.subarray(1, 4).toString('ascii') === 'PNG', 'Social preview must be a PNG.');
check(social.readUInt32BE(16) === 1729 && social.readUInt32BE(20) === 910, 'Social preview dimensions must match OG metadata.');
check(sha256('assets/social-preview.png') === '7372973d8c53e54f457b261b5b2a1f419a9b4a66aeb3737a1a62117506c5c2e7', 'Social preview changed without review.');

const robots = read('robots.txt').trim();
check(robots === 'User-agent: *\nAllow: /\n\nSitemap: https://parkio.dev/sitemap.xml', 'robots.txt contract changed.');

const sitemap = read('sitemap.xml');
const sitemapUrls = [...sitemap.matchAll(/<loc>([^<]+)<\/loc>/g)].map((match) => match[1]);
check(JSON.stringify(sitemapUrls) === JSON.stringify([
  'https://parkio.dev/',
  'https://parkio.dev/privacy/',
  'https://parkio.dev/terms/',
]), 'sitemap.xml must contain only the approved marketing URLs.');

check(sha256('.htaccess') === '26296cb6265bb7b09af9dce104838643d7921fdb21c12fcb91f076541fd3ede2', '.htaccess security policy changed from the imported live baseline.');
check(read('waitlist/confirm/index.html').includes('waitlist-confirm-form'), 'Confirm page must POST via form.');
check(read('waitlist/unsubscribe/index.html').includes('waitlist-withdraw-form'), 'Unsubscribe page must POST via form.');
check(!/method=["']get["']/i.test(read('waitlist/confirm/index.html')), 'Confirm must not use GET form method.');

if (failures.length > 0) {
  failures.forEach((failure) => process.stderr.write(`FAIL: ${failure}\n`));
  process.stderr.write(`marketing_validation_failed=${failures.length}\n`);
  process.exit(1);
}

process.stdout.write(`marketing_required_files=${requiredFiles.length}\n`);
process.stdout.write(`marketing_html_files=${htmlFiles.length}\n`);
process.stdout.write('marketing_broken_internal_links=0\n');
process.stdout.write('marketing_broken_required_assets=0\n');
process.stdout.write('marketing_validation=PASS\n');
