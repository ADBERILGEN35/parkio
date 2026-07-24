/**
 * WP-07 mobile-v2 foundation guardrails.
 * Protects SDK/QueryClient ownership, signal forwarding, cancel-safe retries,
 * SecureStore token path, and no imports from legacy mobile / Web src.
 */
import { parse } from '@typescript-eslint/typescript-estree';

function normalizePath(repositoryPath) {
  return String(repositoryPath).replace(/\\/g, '/');
}

function isTestPath(repositoryPath) {
  const normalized = normalizePath(repositoryPath);
  return (
    normalized.includes('/test/') ||
    /\.(?:test|spec)\.[jt]sx?$/.test(normalized) ||
    normalized.includes('/__tests__/')
  );
}

function isMobileV2Path(repositoryPath) {
  const normalized = normalizePath(repositoryPath);
  return (
    normalized.startsWith('apps/mobile-v2/') ||
    normalized.startsWith('frontend/apps/mobile-v2/')
  );
}

function toMobileRelative(repositoryPath) {
  const normalized = normalizePath(repositoryPath);
  return normalized
    .replace(/^frontend\//, '')
    .replace(/^apps\/mobile-v2\//, '');
}

function synTaxTree(source) {
  return parse(source, {
    comment: false,
    errorOnUnknownASTType: false,
    jsx: true,
    loc: true,
    range: true,
    tokens: false,
  });
}

function walk(node, visit) {
  if (!node || typeof node !== 'object') return;
  visit(node);
  for (const value of Object.values(node)) {
    if (Array.isArray(value)) {
      for (const child of value) walk(child, visit);
    } else if (value && typeof value === 'object' && value.type) {
      walk(value, visit);
    }
  }
}

function propertyName(node) {
  if (!node || node.type !== 'Property') return null;
  if (node.key?.type === 'Identifier') return node.key.name;
  if (node.key?.type === 'Literal' || node.key?.type === 'StringLiteral') {
    return typeof node.key.value === 'string' ? node.key.value : null;
  }
  return null;
}

function sourceSliceHasSignal(source, node) {
  if (!node?.range) return false;
  return source.slice(node.range[0], node.range[1]).includes('signal');
}

function isQueryOptionsOwner(repositoryPath) {
  const rel = toMobileRelative(repositoryPath);
  return (
    rel.startsWith('src/data/query-options/') &&
    rel.endsWith('.ts') &&
    !rel.endsWith('.test.ts')
  );
}

/**
 * @param {string} source
 * @param {string} repositoryPath
 * @returns {{ line: number, rule: string, detail: string }[]}
 */
export function findWp07MobileFoundationViolations(source, repositoryPath) {
  const normalized = normalizePath(repositoryPath);
  if (!isMobileV2Path(normalized) && !normalized.includes('apps/mobile-v2/')) {
    // Allow fixture paths like apps/mobile-v2/src/... in tests
    if (!normalized.startsWith('apps/mobile-v2/')) {
      return [];
    }
  }
  if (isTestPath(normalized)) return [];

  const violations = [];
  const rel = toMobileRelative(normalized);

  // Forbidden cross-app imports
  if (
    /from\s+['"][^'"]*apps\/web\/src/.test(source) ||
    /from\s+['"]@\/..\/web/.test(source) ||
    /from\s+['"][^'"]*apps\/mobile\//.test(source)
  ) {
    violations.push({
      line: 1,
      rule: 'wp07-no-cross-app-imports',
      detail: 'mobile-v2 must not import from apps/web/src or legacy apps/mobile.',
    });
  }

  // Token persistence must not use AsyncStorage (ignore comment-only mentions).
  const asyncStorageImport = /from\s+['"]@react-native-async-storage\/async-storage['"]/.test(source);
  const asyncStorageCall = /AsyncStorage\.(?:setItem|getItem|multiSet|multiGet|removeItem)/.test(source);
  if ((asyncStorageImport || asyncStorageCall) && /token/i.test(source + rel)) {
    violations.push({
      line: 1,
      rule: 'wp07-no-async-storage-tokens',
      detail: 'Do not persist access/refresh tokens via AsyncStorage; use SecureStore.',
    });
  }

  // SDK construction ownership
  const sdkOwner = rel === 'src/services/api.ts';
  if (!sdkOwner && /createApiClient\s*\(/.test(source)) {
    violations.push({
      line: 1,
      rule: 'wp07-single-sdk-owner',
      detail: 'createApiClient may only appear in src/services/api.ts.',
    });
  }

  // QueryClient construction ownership
  const queryOwners = new Set([
    'src/providers/query-client.ts',
    'src/test/renderWithProviders.tsx',
  ]);
  if (!queryOwners.has(rel) && /new\s+QueryClient\s*\(/.test(source)) {
    violations.push({
      line: 1,
      rule: 'wp07-single-query-client-owner',
      detail: 'new QueryClient may only appear in query-client.ts or test harness.',
    });
  }
  if (rel !== 'src/providers/query-client.ts' && /createMobileQueryClient\s*\(/.test(source) && rel !== 'src/providers/QueryProvider.tsx') {
    // QueryProvider is the only caller of the factory
    if (rel !== 'src/providers/QueryProvider.tsx') {
      violations.push({
        line: 1,
        rule: 'wp07-single-query-client-owner',
        detail: 'createMobileQueryClient may only be called from QueryProvider.',
      });
    }
  }

  // Screen-level global clear
  if (
    (rel.startsWith('app/') || rel.startsWith('src/features/') || rel.startsWith('src/components/')) &&
    /queryClient\.clear\s*\(/.test(source)
  ) {
    violations.push({
      line: 1,
      rule: 'wp07-no-screen-query-clear',
      detail: 'Do not call queryClient.clear() from screens/features; use session cache sync.',
    });
  }

  // Cancel-safe retry policy
  if (rel === 'src/providers/query-client.ts') {
    if (!source.includes('CancellationError')) {
      violations.push({
        line: 1,
        rule: 'wp07-no-retry-on-cancellation',
        detail: 'shouldRetryQuery must treat CancellationError as non-retryable.',
      });
    }
  }

  // Canonical query-options must forward signal
  if (isQueryOptionsOwner(normalized)) {
    let tree;
    try {
      tree = synTaxTree(source);
    } catch {
      return violations;
    }
    walk(tree, (node) => {
      if (node.type !== 'Property' || propertyName(node) !== 'queryFn') return;
      if (!sourceSliceHasSignal(source, node.value)) {
        violations.push({
          line: node.loc?.start?.line ?? 1,
          rule: 'wp07-query-options-abort-signal',
          detail: 'Canonical query-options queryFn must forward AbortSignal (destructure signal / pass to SDK).',
        });
      }
    });
  }

  return violations;
}