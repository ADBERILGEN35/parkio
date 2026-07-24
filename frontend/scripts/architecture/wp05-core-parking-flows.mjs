/**
 * WP-05 core parking-flow guardrails.
 * Rejects page-level parking mutation API calls that bypass the data-layer
 * mutation factories, duplicate spot-cache helpers outside data/parking, and
 * page-level Smart Return cache writes that bypass applySmartReturnSettings.
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

function isParkingDataOwner(repositoryPath) {
  const normalized = normalizePath(repositoryPath);
  return (
    normalized.startsWith('apps/web/src/data/mutation-options/') ||
    normalized.startsWith('apps/web/src/data/parking/') ||
    normalized.startsWith('apps/web/src/data/hooks/')
  );
}

function isPageOrComponent(repositoryPath) {
  const normalized = normalizePath(repositoryPath);
  return (
    normalized.includes('/pages/') ||
    normalized.includes('/components/') ||
    normalized.includes('/features/')
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

function isApiMember(node, apiName) {
  if (!node || node.type !== 'MemberExpression') return false;
  if (node.object?.type === 'Identifier' && node.object.name === apiName) return true;
  return (
    node.object?.type === 'MemberExpression' &&
    node.object.property?.type === 'Identifier' &&
    node.object.property.name === apiName
  );
}

const FORBIDDEN_PARKING_METHODS = new Set([
  'verifySpot',
  'claimSpot',
  'createParkingSpot',
]);

/**
 * @param {string} source
 * @param {string} repositoryPath
 * @returns {{ line: number, rule: string, detail: string }[]}
 */
export function findWp05CoreParkingViolations(source, repositoryPath) {
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
      !isParkingDataOwner(normalized) &&
      isPageOrComponent(normalized) &&
      node.type === 'MemberExpression' &&
      node.property?.type === 'Identifier' &&
      FORBIDDEN_PARKING_METHODS.has(node.property.name) &&
      isApiMember(node, 'parkingApi')
    ) {
      violations.push({
        line: node.loc?.start.line ?? 1,
        rule: 'wp05-page-parking-mutation-api',
        detail:
          `pages/components must not call parkingApi.${node.property.name}(); use data/hooks/useParkingMutations`,
      });
    }

    if (
      !isParkingDataOwner(normalized) &&
      isPageOrComponent(normalized) &&
      node.type === 'MemberExpression' &&
      node.property?.type === 'Identifier' &&
      node.property.name === 'createReport' &&
      isApiMember(node, 'moderationApi')
    ) {
      violations.push({
        line: node.loc?.start.line ?? 1,
        rule: 'wp05-page-report-mutation-api',
        detail:
          'pages/components must not call moderationApi.createReport(); use useReportSpotMutation for parking reports',
      });
    }

    if (
      node.type === 'FunctionDeclaration' &&
      node.id?.type === 'Identifier' &&
      (node.id.name === 'applyParkingSpotUpdate' || node.id.name === 'applySpotUpdate') &&
      normalizePath(repositoryPath) !== 'apps/web/src/data/parking/spotCache.ts'
    ) {
      violations.push({
        line: node.loc?.start.line ?? 1,
        rule: 'wp05-duplicate-spot-cache-helper',
        detail:
          'applyParkingSpotUpdate / applySpotUpdate is owned by apps/web/src/data/parking/spotCache.ts',
      });
    }

    if (
      isPageOrComponent(normalized) &&
      node.type === 'CallExpression' &&
      node.callee?.type === 'MemberExpression' &&
      node.callee.property?.type === 'Identifier' &&
      node.callee.property.name === 'setQueryData' &&
      node.arguments?.[0]?.type === 'CallExpression' &&
      node.arguments[0].callee?.type === 'MemberExpression' &&
      node.arguments[0].callee.object?.type === 'Identifier' &&
      node.arguments[0].callee.object.name === 'meKeys' &&
      node.arguments[0].callee.property?.type === 'Identifier' &&
      node.arguments[0].callee.property.name === 'smartReturn'
    ) {
      violations.push({
        line: node.loc?.start.line ?? 1,
        rule: 'wp05-page-smart-return-cache-write',
        detail:
          'pages must not setQueryData(meKeys.smartReturn()); use Smart Return mutation hooks',
      });
    }
  });

  return violations;
}

export function wp05FixtureAllowlist() {
  return {
    mutationOwners: [
      'apps/web/src/data/mutation-options/parking.ts',
      'apps/web/src/data/mutation-options/smart-return.ts',
      'apps/web/src/data/parking/spotCache.ts',
    ],
  };
}