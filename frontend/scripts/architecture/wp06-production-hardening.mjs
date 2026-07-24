/**
 * WP-06 production-hardening guardrails.
 * Protects AbortSignal forwarding in canonical Web query-options owners and
 * rejects QueryClient retry policies that retry CancellationError.
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

function syntaxTree(source) {
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

function isQueryOptionsOwner(repositoryPath) {
  const normalized = normalizePath(repositoryPath);
  return (
    normalized.startsWith('apps/web/src/data/query-options/') &&
    normalized.endsWith('.ts') &&
    !normalized.endsWith('.test.ts')
  );
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

/**
 * @param {string} source
 * @param {string} repositoryPath
 * @returns {{ line: number, rule: string, detail: string }[]}
 */
export function findWp06ProductionHardeningViolations(source, repositoryPath) {
  const normalized = normalizePath(repositoryPath);
  if (!normalized.startsWith('apps/web/src/') && !normalized.startsWith('packages/api-client/src/')) {
    return [];
  }
  if (isTestPath(normalized)) return [];

  const violations = [];
  let tree;
  try {
    tree = syntaxTree(source);
  } catch {
    return violations;
  }

  if (isQueryOptionsOwner(normalized)) {
    walk(tree, (node) => {
      if (node.type !== 'Property' || propertyName(node) !== 'queryFn') return;
      if (!sourceSliceHasSignal(source, node.value)) {
        violations.push({
          line: node.loc?.start.line ?? 1,
          rule: 'wp06-query-options-abort-signal',
          detail:
            'canonical query-options queryFn must forward AbortSignal (destructure signal / pass to SDK)',
        });
      }
    });
  }

  if (normalized === 'apps/web/src/providers/query-client.ts') {
    if (!source.includes('CancellationError')) {
      violations.push({
        line: 1,
        rule: 'wp06-no-retry-on-cancellation',
        detail:
          'shouldRetryQuery must treat CancellationError as non-retryable (import and guard)',
      });
    }
  }

  return violations;
}
