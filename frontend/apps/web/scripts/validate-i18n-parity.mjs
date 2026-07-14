import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const localesRoot = path.resolve(__dirname, '../src/i18n/locales');

function flattenKeys(obj, prefix = '') {
  const keys = [];
  for (const [key, value] of Object.entries(obj)) {
    const next = prefix ? `${prefix}.${key}` : key;
    if (value && typeof value === 'object' && !Array.isArray(value)) {
      keys.push(...flattenKeys(value, next));
    } else {
      keys.push(next);
    }
  }
  return keys;
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

  const tr = JSON.parse(fs.readFileSync(trPath, 'utf8'));
  const en = JSON.parse(fs.readFileSync(enPath, 'utf8'));
  const trKeys = new Set(flattenKeys(tr));
  const enKeys = new Set(flattenKeys(en));

  for (const key of trKeys) {
    if (!enKeys.has(key)) {
      console.error(`[${file}] Missing EN key: ${key}`);
      failed = true;
    }
  }
  for (const key of enKeys) {
    if (!trKeys.has(key)) {
      console.error(`[${file}] Missing TR key: ${key}`);
      failed = true;
    }
  }

  for (const key of trKeys) {
    const trVal = key.split('.').reduce((acc, part) => acc?.[part], tr);
    const enVal = key.split('.').reduce((acc, part) => acc?.[part], en);
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