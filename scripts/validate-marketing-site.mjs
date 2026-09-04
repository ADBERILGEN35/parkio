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
  'privacy/index.html',
  'terms/index.html',
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
const publicCopy = `${index}\n${notFound}\n${manifest}`;

check((index.match(/<h1(?:\s|>)/gi) ?? []).length === 1, 'Marketing index must contain exactly one h1.');
for (const semanticTag of ['nav', 'main', 'footer']) {
  check(new RegExp(`<${semanticTag}(?:\\s|>)`, 'i').test(index), `Marketing index must contain semantic <${semanticTag}>.`);
}

const exactContent = [
  '<link rel="canonical" href="https://parkio.dev/">',
  '<meta property="og:url" content="https://parkio.dev/">',
  '<meta property="og:image" content="https://parkio.dev/assets/social-preview.png">',
  '<meta name="twitter:image" content="https://parkio.dev/assets/social-preview.png">',
  'Oğuzhan Taşyaran',
  'How Parkio works as a business',
  'Roadmap',
  'Parkio today',
  'Account registration remains controlled.',
  'Public access is prepared and remains disabled',
  'mailto:info@parkio.dev',
  'href="/privacy/"',
  'href="/terms/"',
];
exactContent.forEach((value) => check(index.includes(value), `Required marketing content missing: ${value}`));

for (const anchor of ['#product', '#how', '#trust', '#business', '#roadmap', '#about']) {
  check(index.includes(`href="${anchor}"`), `Primary navigation target missing: ${anchor}`);
  check(index.includes(`id="${anchor.slice(1)}"`), `Section id missing: ${anchor}`);
}

for (const ctaId of ['header-product-cta', 'primary-product-cta', 'today-product-cta']) {
  const pattern = new RegExp(`<a[^>]*id="${ctaId}"[^>]*href="https://app\\.parkio\\.dev/"[^>]*>\\s*Open Parkio`, 'i');
  check(pattern.test(index), `Pre-enable CTA contract failed for ${ctaId}.`);
}
check(!index.includes('https://app.parkio.dev/explore'), 'Pre-enable marketing must not link to /explore.');

const bannedSignals = [
  /\billustrative\b/i,
  /\bmock(?:up)?s?\b/i,
  /\bprototype\b/i,
  /\bplaceholder\b/i,
  /\bcoming soon\b/i,
  /\bunder construction\b/i,
  /\bwaitlist\b/i,
  /\brequest (?:a )?demo\b/i,
  /\btrusted by\b/i,
  /\bdrivers across\b/i,
  /\bthousands of\b/i,
  /\bfive[- ]star\b/i,
  /\btestimonial(?:s)?\b/i,
  /\bmunicipal partner(?:ship)?\b/i,
  /\bpublic explore is live\b/i,
];
bannedSignals.forEach((pattern) => check(!pattern.test(publicCopy), `Unsupported public signal found: ${pattern}`));

check(!/href\s*=\s*["'](?:|#)["']/i.test(index), 'Empty or fragment-only href found.');
check(!/linkedin\.com/i.test(index), 'LinkedIn URL must be absent until operator input is supplied.');

const jsonLdMatch = index.match(/<script type="application\/ld\+json">([\s\S]*?)<\/script>/i);
check(Boolean(jsonLdMatch), 'Organization JSON-LD is missing.');
if (jsonLdMatch) {
  try {
    const graph = JSON.parse(jsonLdMatch[1])['@graph'];
    const organization = graph.find((entry) => entry['@type'] === 'Organization');
    check(organization?.founder?.name === 'Oğuzhan Taşyaran', 'JSON-LD founder name is incorrect.');
    check(organization?.founder?.jobTitle === 'Founder', 'JSON-LD founder jobTitle is incorrect.');
    check(!Object.hasOwn(organization?.founder ?? {}, 'sameAs'), 'JSON-LD founder.sameAs must be omitted without operator input.');
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

check(sha256('privacy/index.html') === 'ff42df98361959c6eda1667c97ee2b5740147a9ec89b43ca5ba1e0148a1e98d8', 'Privacy policy changed from the imported live baseline.');
check(sha256('terms/index.html') === '3bd15878f4c1c3928722cfb0ddf5b1e8232307cfebe4c7d3f4f9c6193e4e8403', 'Terms changed from the imported live baseline.');
check(sha256('.htaccess') === '452af8382fee516fc66f1dd325667381eac4004fa7c3221b7c58cffad5f1d594', '.htaccess security policy changed from the imported live baseline.');

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
