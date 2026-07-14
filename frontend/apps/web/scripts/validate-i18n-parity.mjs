import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const localesRoot = path.resolve(__dirname, '../src/i18n/locales');

/** Pseudo-icon / unresolved markers that must never appear in catalogs. */
const TOKEN_LEAK_RE = /(?:\+)?__[A-Z0-9]+(?:__[A-Z0-9]+)*__/;

function flattenEntries(obj, prefix = '') {
  const entries = [];
  for (const [key, value] of Object.entries(obj)) {
    const next = prefix ? `${prefix}.${key}` : key;
    if (value && typeof value === 'object' && !Array.isArray(value)) {
      entries.push(...flattenEntries(value, next));
    } else {
      entries.push([next, value]);
    }
  }
  return entries;
}

function extractPlaceholders(value) {
  if (typeof value !== 'string') return [];
  const matches = value.match(/\{\{[^}]+\}\}/g);
  return matches ? [...matches].sort() : [];
}

function listNamespaceFiles() {
  const dir = path.join(localesRoot, 'tr');
  return fs.readdirSync(dir).filter((f) => f.endsWith('.json')).sort();
}

let failed = false;

for (const file of listNamespaceFiles()) {
  const trPath = path.join(localesRoot, 'tr', file);
  const enPath = path.join(localesRoot, 'en', file);
  if (!fs.existsSync(enPath)) {
    console.error(`Missing English file for ${file}`);
    failed = true;
    continue;
  }

  let tr;
  let en;
  try {
    tr = JSON.parse(fs.readFileSync(trPath, 'utf8'));
    en = JSON.parse(fs.readFileSync(enPath, 'utf8'));
  } catch (err) {
    console.error(`[${file}] Malformed JSON: ${err.message}`);
    failed = true;
    continue;
  }

  const trMap = new Map(flattenEntries(tr));
  const enMap = new Map(flattenEntries(en));

  for (const key of trMap.keys()) {
    if (!enMap.has(key)) {
      console.error(`[${file}] Missing EN key: ${key}`);
      failed = true;
    }
  }
  for (const key of enMap.keys()) {
    if (!trMap.has(key)) {
      console.error(`[${file}] Missing TR key: ${key}`);
      failed = true;
    }
  }

  for (const [key, trVal] of trMap) {
    const enVal = enMap.get(key);
    if (typeof trVal === 'string' && trVal.trim() === '') {
      console.error(`[${file}] Empty TR value: ${key}`);
      failed = true;
    }
    if (typeof enVal === 'string' && enVal.trim() === '') {
      console.error(`[${file}] Empty EN value: ${key}`);
      failed = true;
    }
    if (typeof trVal === 'string' && TOKEN_LEAK_RE.test(trVal)) {
      console.error(`[${file}] Token leak in TR ${key}: ${trVal}`);
      failed = true;
    }
    if (typeof enVal === 'string' && TOKEN_LEAK_RE.test(enVal)) {
      console.error(`[${file}] Token leak in EN ${key}: ${enVal}`);
      failed = true;
    }

    const trPh = extractPlaceholders(trVal).join(',');
    const enPh = extractPlaceholders(enVal).join(',');
    if (trPh !== enPh) {
      console.error(`[${file}] Placeholder mismatch for ${key}: tr=[${trPh}] en=[${enPh}]`);
      failed = true;
    }
  }
}

if (failed) {
  process.exit(1);
}

console.log('i18n parity OK');
