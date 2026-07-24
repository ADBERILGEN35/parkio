/**
 * WP-04 frontend data-architecture guardrails.
 * Rejects second production QueryClient ownership paths, global clear outside
 * the approved session-cache owner, duplicate meKeys registries, and inline
 * migrated-domain query keys in feature/page code.
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

function isApprovedSessionCacheOwner(repositoryPath) {
  const normalized = normalizePath(repositoryPath);
  return (
    normalized === 'apps/web/src/data/sessionQueryCache.ts' ||
    normalized === 'apps/web/src/data/SessionQueryCacheSync.tsx'
  );
}

function isCanonicalKeysOwner(repositoryPath) {
  return normalizePath(repositoryPath) === 'apps/web/src/data/keys.ts';
}

function isDataLayerPath(repositoryPath) {
  const normalized = normalizePath(repositoryPath);
  return (
    normalized.startsWith('apps/web/src/data/') ||
    normalized === 'apps/web/src/lib/gamificationCache.ts' ||
    normalized === 'apps/web/src/providers/query-client.ts'
  );
}

function syntaxTree(source) {
  return parse(source, {
    comment: false,
    errorOnUnknownASTType: false,
    jsx: true,
    loc: true,
    range: false,
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

function arrayFirstString(elements) {
  const first = elements?.[0];
  if (!first) return null;
  if (first.type === 'StringLiteral' || first.type === 'Literal') {
    return typeof first.value === 'string' ? first.value : null;
  }
  return null;
}

function isMigratedDomainRoot(value) {
  return value === 'me' || value === 'parking' || value === 'notifications' || value === 'reports';
}

/**
 * @param {string} source
 * @param {string} repositoryPath
 * @returns {{ line: number, rule: string, detail: string }[]}
 */
export function findWp04DataArchitectureViolations(source, repositoryPath) {
  const normalized = normalizePath(repositoryPath);
  if (!normalized.startsWith('apps/web/src/')) return [];
  if (isTestPath(normalized)) return [];

  const violations = [];
  let tree;
  try {
    tree = syntaxTree(source);
  } catch {
    return violations;
  }

  walk(tree, (node) => {
    if (
      node.type === 'CallExpression' &&
      node.callee?.type === 'MemberExpression' &&
      node.callee.property?.type === 'Identifier' &&
      node.callee.property.name === 'clear' &&
      node.callee.object?.type === 'Identifier' &&
      /queryClient/i.test(node.callee.object.name) &&
      !isApprovedSessionCacheOwner(normalized)
    ) {
      violations.push({
        line: node.loc?.start.line ?? 1,
        rule: 'wp04-no-global-query-clear',
        detail:
          'queryClient.clear() is forbidden outside apps/web/src/data/sessionQueryCache.ts; use scoped clearUserSessionQueries',
      });
    }

    if (
      node.type === 'ExportNamedDeclaration' &&
      node.declaration?.type === 'VariableDeclaration'
    ) {
      for (const decl of node.declaration.declarations ?? []) {
        if (
          decl.id?.type === 'Identifier' &&
          (decl.id.name === 'meKeys' ||
            decl.id.name === 'parkingKeys' ||
            decl.id.name === 'notificationsKeys') &&
          !isCanonicalKeysOwner(normalized)
        ) {
          violations.push({
            line: decl.loc?.start.line ?? 1,
            rule: 'wp04-duplicate-query-key-registry',
            detail: `canonical query-key factory '${decl.id.name}' is owned by apps/web/src/data/keys.ts`,
          });
        }
      }
    }

    if (
      !isDataLayerPath(normalized) &&
      (normalized.includes('/pages/') ||
        normalized.includes('/components/') ||
        normalized.includes('/i18n/') ||
        normalized.includes('/features/')) &&
      node.type === 'Property' &&
      ((node.key?.type === 'Identifier' && node.key.name === 'queryKey') ||
        (node.key?.type === 'StringLiteral' && node.key.value === 'queryKey'))
    ) {
      const value = node.value;
      if (value?.type === 'ArrayExpression') {
        const root = arrayFirstString(value.elements);
        if (isMigratedDomainRoot(root)) {
          violations.push({
            line: node.loc?.start.line ?? 1,
            rule: 'wp04-inline-migrated-query-key',
            detail: `inline queryKey root '${root}' must use canonical factories from @/data/keys`,
          });
        }
      }
    }

    if (
      (normalized.includes('/pages/') || normalized.includes('/components/')) &&
      (node.type === 'StringLiteral' || node.type === 'Literal') &&
      typeof node.value === 'string' &&
      /^Bearer\s+/i.test(node.value)
    ) {
      violations.push({
        line: node.loc?.start.line ?? 1,
        rule: 'wp04-no-feature-auth-headers',
        detail: 'feature code must not construct Authorization bearer headers; use the injected SDK',
      });
    }
  });

  return violations;
}

export function wp04FixtureAllowlist() {
  return {
    sessionCacheOwners: [
      'apps/web/src/data/sessionQueryCache.ts',
      'apps/web/src/data/SessionQueryCacheSync.tsx',
    ],
    keysOwner: 'apps/web/src/data/keys.ts',
  };
}