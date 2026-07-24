import { readdir, readFile, stat } from 'node:fs/promises';
import { builtinModules } from 'node:module';
import path from 'node:path';
import { gzipSync } from 'node:zlib';
import { parse } from '@typescript-eslint/typescript-estree';
import {
  findCredentialPersistenceAstViolations,
  findCrossTabSecurityAstViolations,
  findWebAuthOwnershipAstViolations,
} from './auth-guardrail-ast.mjs';

const SOURCE_EXTENSIONS = new Set(['.js', '.jsx', '.mjs', '.ts', '.tsx']);
const SKIPPED_DIRECTORIES = new Set([
  '.expo',
  '.git',
  'build',
  'coverage',
  'dist',
  'node_modules',
]);

const DIRECT_HTTP_PACKAGES = [
  'axios',
  'cross-fetch',
  'got',
  'ky',
  'node-fetch',
  'ofetch',
  'superagent',
  'undici',
];

const PLATFORM_PACKAGES = [
  '@tanstack/react-query',
  'react',
  'react-dom',
  'zustand',
];

const NODE_PACKAGES = new Set(
  builtinModules.map((moduleName) => moduleName.replace(/^node:/, '').split('/')[0]),
);

function packageMatches(specifier, packageName) {
  return specifier === packageName || specifier.startsWith(`${packageName}/`);
}

function tokenizeModuleSyntax(source) {
  const tokens = [];
  let index = 0;
  let line = 1;

  function scanQuotedString(quote) {
    const tokenLine = line;
    let value = '';
    index += 1;
    while (index < source.length) {
      if (source[index] === '\\') {
        if (source[index + 1] === '\n') {
          line += 1;
        }
        index += 2;
        continue;
      }
      if (source[index] === quote) {
        index += 1;
        break;
      }
      if (source[index] === '\n') {
        line += 1;
      }
      value += source[index];
      index += 1;
    }
    tokens.push({ kind: 'string', value, line: tokenLine });
  }

  function scanTemplate() {
    index += 1;
    while (index < source.length) {
      if (source[index] === '\\') {
        if (source[index + 1] === '\n') {
          line += 1;
        }
        index += 2;
        continue;
      }
      if (source[index] === '`') {
        index += 1;
        return;
      }
      if (source[index] === '$' && source[index + 1] === '{') {
        index += 2;
        scanCode(true);
        continue;
      }
      if (source[index] === '\n') {
        line += 1;
      }
      index += 1;
    }
  }

  function scanCode(stopAtTemplateExpression) {
    let nestedBraceDepth = 0;

    while (index < source.length) {
      const current = source[index];
      const next = source[index + 1];

      if (/\s/.test(current)) {
        if (current === '\n') {
          line += 1;
        }
        index += 1;
        continue;
      }

      if (current === '/' && next === '/') {
        index += 2;
        while (index < source.length && source[index] !== '\n') {
          index += 1;
        }
        continue;
      }

      if (current === '/' && next === '*') {
        index += 2;
        while (index < source.length && !(source[index] === '*' && source[index + 1] === '/')) {
          if (source[index] === '\n') {
            line += 1;
          }
          index += 1;
        }
        index += 2;
        continue;
      }

      if (current === "'" || current === '"') {
        scanQuotedString(current);
        continue;
      }

      if (current === '`') {
        scanTemplate();
        continue;
      }

      if (stopAtTemplateExpression && current === '}' && nestedBraceDepth === 0) {
        index += 1;
        return;
      }

      if (/[A-Za-z_$]/.test(current)) {
        const tokenLine = line;
        let value = current;
        index += 1;
        while (index < source.length && /[A-Za-z0-9_$]/.test(source[index])) {
          value += source[index];
          index += 1;
        }
        tokens.push({ kind: 'identifier', value, line: tokenLine });
        continue;
      }

      if (stopAtTemplateExpression && current === '{') {
        nestedBraceDepth += 1;
      } else if (stopAtTemplateExpression && current === '}') {
        nestedBraceDepth -= 1;
      }
      tokens.push({ kind: 'punctuation', value: current, line });
      index += 1;
    }
  }

  scanCode(false);
  return tokens;
}

export function extractModuleSpecifiers(source) {
  const matches = [];
  const tokens = tokenizeModuleSyntax(source);

  for (let index = 0; index < tokens.length; index += 1) {
    const token = tokens[index];
    if (token.kind !== 'identifier') {
      continue;
    }

    if (token.value === 'require') {
      if (tokens[index + 1]?.value === '(' && tokens[index + 2]?.kind === 'string') {
        matches.push({ specifier: tokens[index + 2].value, line: token.line });
      }
      continue;
    }

    if (token.value !== 'import' && token.value !== 'export') {
      continue;
    }
    if (tokens[index + 1]?.value === '.') {
      continue;
    }
    if (tokens[index + 1]?.kind === 'string') {
      matches.push({ specifier: tokens[index + 1].value, line: token.line });
      continue;
    }
    if (
      token.value === 'import' &&
      tokens[index + 1]?.value === '(' &&
      tokens[index + 2]?.kind === 'string'
    ) {
      matches.push({ specifier: tokens[index + 2].value, line: token.line });
      continue;
    }

    const searchLimit = Math.min(tokens.length, index + 100);
    for (let cursor = index + 1; cursor < searchLimit; cursor += 1) {
      if (tokens[cursor].value === ';') {
        break;
      }
      if (tokens[cursor].value === 'from' && tokens[cursor + 1]?.kind === 'string') {
        matches.push({ specifier: tokens[cursor + 1].value, line: token.line });
        break;
      }
    }
  }

  return matches;
}

export function findDirectHttpViolations(source) {
  const violations = [];

  for (const moduleImport of extractModuleSpecifiers(source)) {
    if (DIRECT_HTTP_PACKAGES.some((packageName) => packageMatches(moduleImport.specifier, packageName))) {
      violations.push({
        line: moduleImport.line,
        rule: 'no-direct-http-package',
        detail: `direct HTTP dependency '${moduleImport.specifier}'`,
      });
    }
  }

  const tokens = tokenizeModuleSyntax(source);
  for (let index = 0; index < tokens.length; index += 1) {
    const token = tokens[index];
    if (token.kind === 'identifier' && token.value === 'fetch') {
      let callIndex = index + 1;
      if (tokens[callIndex]?.value === '?' && tokens[callIndex + 1]?.value === '.') {
        callIndex += 2;
      }
      if (tokens[callIndex]?.value === '(') {
        violations.push({
          line: token.line,
          rule: 'no-direct-http-call',
          detail: 'direct fetch() call',
        });
      }
    }

    if (
      token.kind === 'identifier' &&
      token.value === 'XMLHttpRequest' &&
      tokens[index - 1]?.value === 'new' &&
      tokens[index + 1]?.value === '('
    ) {
      violations.push({
        line: token.line,
        rule: 'no-direct-http-call',
        detail: 'direct XMLHttpRequest construction',
      });
    }
  }

  return violations;
}

/** Detects attempts to persist access or refresh credentials in browser-managed storage. */
export function findCredentialPersistenceViolations(source) {
  return findCredentialPersistenceAstViolations(source);
}

/** Enforces one credential-free, inline, versioned BroadcastChannel owner. */
export function findCrossTabSecurityViolations(source, repositoryPath) {
  return findCrossTabSecurityAstViolations(source, normalizeRepositoryPath(repositoryPath));
}

export function findDependencyBoundaryViolations(source, zone) {
  const violations = [];

  for (const moduleImport of extractModuleSpecifiers(source)) {
    const { specifier, line } = moduleImport;

    if (zone === 'application') {
      const deepClientImport =
        specifier.startsWith('@parkio/api-client/') ||
        /(?:^|\/)packages\/api-client(?:\/|$)/.test(specifier);
      if (deepClientImport) {
        violations.push({
          line,
          rule: 'api-client-public-entrypoint-only',
          detail: `application import bypasses @parkio/api-client public entrypoint: '${specifier}'`,
        });
      }
    }

    if (zone === 'api-client') {
      const platformDependency =
        PLATFORM_PACKAGES.some((packageName) => packageMatches(specifier, packageName)) ||
        specifier === 'expo' ||
        specifier.startsWith('expo-') ||
        specifier.startsWith('@expo/') ||
        specifier === 'react-native' ||
        specifier.startsWith('react-native-') ||
        specifier.startsWith('@react-native/') ||
        specifier.startsWith('@react-native-community/') ||
        specifier.startsWith('@react-native-async-storage/') ||
        specifier.startsWith('@react-native-firebase/') ||
        specifier.startsWith('@capacitor/');
      const nodeDependency =
        specifier.startsWith('node:') ||
        [...NODE_PACKAGES].some((packageName) => packageMatches(specifier, packageName));
      const applicationDependency =
        specifier.startsWith('@/') ||
        /(?:^|\/)apps(?:\/|$)/.test(specifier);

      if (platformDependency || nodeDependency || applicationDependency) {
        violations.push({
          line,
          rule: 'api-client-platform-neutrality',
          detail: `api-client imports forbidden dependency '${specifier}'`,
        });
      }
    }

    if (
      (zone === 'types' || zone === 'validation') &&
      packageMatches(specifier, '@parkio/api-client')
    ) {
      violations.push({
        line,
        rule: 'shared-package-dependency-direction',
        detail: `${zone} package must not depend on @parkio/api-client`,
      });
    }
  }

  return violations;
}

export function findPackageManifestBoundaryViolations(packageJson, zone) {
  const runtimeDependencies = {
    ...packageJson.dependencies,
    ...packageJson.optionalDependencies,
    ...packageJson.peerDependencies,
  };

  return Object.keys(runtimeDependencies).flatMap((dependency) => {
    const source = `import '${dependency}';`;
    const violations = findDependencyBoundaryViolations(source, zone);
    if (zone === 'application') {
      violations.push(...findDirectHttpViolations(source));
    }
    return violations.map((violation) => ({
      ...violation,
      line: 1,
      rule: 'runtime-dependency-boundary',
    }));
  });
}

const SDK_COMPOSITION_FACTORIES = new Set([
  'createApiClient',
  'createAdminApi',
  'createAnalyticsApi',
  'createAuthApi',
  'createGamificationApi',
  'createGeocodingApi',
  'createMediaApi',
  'createModerationApi',
  'createNotificationsApi',
  'createParkingApi',
  'createUsersApi',
]);

const WEB_COMPOSITION_FACTORY_OWNERS = new Map([
  [
    'createParkioSdk',
    new Set(['apps/web/src/app/sdk.ts', 'apps/web/src/app/runtime.ts']),
  ],
  [
    'createWebQueryClient',
    new Set(['apps/web/src/providers/query-client.ts', 'apps/web/src/app/runtime.ts']),
  ],
  [
    'createWebAppRuntime',
    new Set(['apps/web/src/app/runtime.ts', 'apps/web/src/main.tsx']),
  ],
  [
    'createAuthStore',
    new Set(['apps/web/src/auth/auth-store.ts', 'apps/web/src/app/runtime.ts']),
  ],
  [
    'createAuthSession',
    new Set(['apps/web/src/auth/session.ts', 'apps/web/src/app/runtime.ts']),
  ],
  [
    'createCrossTabSessionSync',
    new Set(['apps/web/src/auth/crossTabSync.ts', 'apps/web/src/auth/session.ts']),
  ],
]);

function queryClientConstructionLines(tokens) {
  const directBindings = new Set(['QueryClient']);
  const namespaceBindings = new Set();

  for (let index = 0; index < tokens.length; index += 1) {
    if (tokens[index].value !== 'import') continue;

    let cursor = index + 1;
    let moduleName;
    while (cursor < tokens.length && tokens[cursor].value !== ';') {
      if (tokens[cursor].value === 'from' && tokens[cursor + 1]?.kind === 'string') {
        moduleName = tokens[cursor + 1].value;
        break;
      }
      cursor += 1;
    }
    if (moduleName !== '@tanstack/react-query') continue;

    for (let binding = index + 1; binding < cursor; binding += 1) {
      if (
        tokens[binding].value === 'QueryClient' &&
        tokens[binding + 1]?.value === 'as' &&
        tokens[binding + 2]?.kind === 'identifier'
      ) {
        directBindings.add(tokens[binding + 2].value);
      }
      if (
        tokens[binding].value === '*' &&
        tokens[binding + 1]?.value === 'as' &&
        tokens[binding + 2]?.kind === 'identifier'
      ) {
        namespaceBindings.add(tokens[binding + 2].value);
      }
    }
  }

  const lines = [];
  for (let index = 0; index < tokens.length - 2; index += 1) {
    if (tokens[index].value !== 'new') continue;

    const constructor = tokens[index + 1];
    if (directBindings.has(constructor?.value) && tokens[index + 2]?.value === '(') {
      lines.push(tokens[index].line);
      continue;
    }
    if (
      namespaceBindings.has(constructor?.value) &&
      tokens[index + 2]?.value === '.' &&
      tokens[index + 3]?.value === 'QueryClient' &&
      tokens[index + 4]?.value === '('
    ) {
      lines.push(tokens[index].line);
    }
  }
  return lines;
}

const ROUTER_FACTORY_EXPORTS = new Set([
  'createBrowserRouter',
  'createHashRouter',
  'createMemoryRouter',
  'createStaticRouter',
]);
const ROUTE_GRAPH_EXPORTS = new Set([
  'Route',
  'Routes',
  'createRoutesFromElements',
  'useRoutes',
]);
const ROUTER_PROVIDER_EXPORT = 'RouterProvider';
const LEGACY_ROUTING_IMPORTS = [
  /^@\/router$/,
  /(?:^|\/)auth\/(?:ProtectedRoute|RoleRoute)$/,
  /(?:^|\/)(?:ProtectedRoute|RoleRoute)$/,
];

function routingSyntaxTree(source) {
  return parse(source, {
    comment: false,
    errorOnUnknownASTType: false,
    jsx: true,
    loc: true,
    range: false,
    sourceType: 'module',
    tokens: false,
  });
}

function routingUnwrap(node) {
  let current = node;
  while (
    current &&
    [
      'ChainExpression',
      'TSAsExpression',
      'TSInstantiationExpression',
      'TSNonNullExpression',
      'TSSatisfiesExpression',
      'TSTypeAssertion',
    ].includes(current.type)
  ) {
    current = current.expression;
  }
  return current;
}

function unwrapTransparentCall(node) {
  let current = routingUnwrap(node);
  while (current?.type === 'CallExpression') {
    const callee = routingUnwrap(current.callee);
    const method =
      callee?.type === 'MemberExpression'
        ? routingStaticPropertyName(callee)
        : null;
    const objectName =
      callee?.type === 'MemberExpression' &&
      routingUnwrap(callee.object)?.type === 'Identifier'
        ? routingUnwrap(callee.object).name
        : null;
    if (
      objectName === 'Object' &&
      ['freeze', 'seal', 'preventExtensions'].includes(method) &&
      current.arguments[0]
    ) {
      current = routingUnwrap(current.arguments[0]);
      continue;
    }
    break;
  }
  return current;
}

function walkRoutingSyntax(node, visitor, parent = null, ancestors = []) {
  if (!node || typeof node !== 'object' || typeof node.type !== 'string') {
    return;
  }
  visitor(node, parent, ancestors);
  const nextAncestors = [...ancestors, node];
  for (const [key, value] of Object.entries(node)) {
    if (['loc', 'parent', 'range', 'tokens'].includes(key)) {
      continue;
    }
    if (Array.isArray(value)) {
      value.forEach((child) =>
        walkRoutingSyntax(child, visitor, node, nextAncestors),
      );
    } else {
      walkRoutingSyntax(value, visitor, node, nextAncestors);
    }
  }
}

function routingLine(node) {
  return node?.loc?.start?.line ?? 1;
}

function routingStaticString(node, staticStrings = new Map()) {
  const expression = routingUnwrap(node);
  if (!expression) {
    return null;
  }
  if (
    expression.type === 'Literal' &&
    typeof expression.value === 'string'
  ) {
    return expression.value;
  }
  if (
    expression.type === 'TemplateLiteral' &&
    expression.expressions.length === 0
  ) {
    return expression.quasis[0]?.value?.cooked ?? null;
  }
  if (expression.type === 'Identifier') {
    return staticStrings.get(expression.name) ?? null;
  }
  return null;
}

function routingStaticPropertyName(node, staticStrings = new Map()) {
  const property = routingUnwrap(node?.property);
  if (!property) {
    return null;
  }
  if (!node.computed && property.type === 'Identifier') {
    return property.name;
  }
  return routingStaticString(property, staticStrings);
}

function routingStaticKeyName(node, staticStrings = new Map()) {
  const key = routingUnwrap(node?.key);
  if (!key) {
    return null;
  }
  if (!node.computed && key.type === 'Identifier') {
    return key.name;
  }
  return routingStaticString(key, staticStrings);
}

function importedName(specifier) {
  if (specifier.imported?.type === 'Identifier') {
    return specifier.imported.name;
  }
  return specifier.imported?.value;
}

function collectReactRouterBindings(ast) {
  const bindings = new Map();
  const namespaces = new Set();
  const declarators = [];
  const constantDeclarators = [];
  const staticStrings = new Map();

  walkRoutingSyntax(ast, (node) => {
    if (node.type === 'ImportDeclaration' && node.source.value === 'react-router-dom') {
      for (const specifier of node.specifiers) {
        if (specifier.type === 'ImportNamespaceSpecifier') {
          namespaces.add(specifier.local.name);
        } else if (specifier.type === 'ImportSpecifier') {
          bindings.set(specifier.local.name, importedName(specifier));
        }
      }
    }
    if (node.type === 'VariableDeclarator' && node.init) {
      declarators.push(node);
    }
    if (node.type === 'VariableDeclaration' && node.kind === 'const') {
      constantDeclarators.push(...node.declarations);
    }
  });

  let changed = true;
  while (changed) {
    changed = false;
    for (const declarator of constantDeclarators) {
      if (declarator.id.type !== 'Identifier' || !declarator.init) {
        continue;
      }
      const value = routingStaticString(declarator.init, staticStrings);
      if (value !== null && staticStrings.get(declarator.id.name) !== value) {
        staticStrings.set(declarator.id.name, value);
        changed = true;
      }
    }
    for (const declarator of declarators) {
      const initializer = routingUnwrap(declarator.init);
      if (declarator.id.type === 'Identifier') {
        if (
          initializer?.type === 'Identifier' &&
          namespaces.has(initializer.name) &&
          !namespaces.has(declarator.id.name)
        ) {
          namespaces.add(declarator.id.name);
          changed = true;
        }
        const resolved = routingBindingName(
          initializer,
          bindings,
          namespaces,
          staticStrings,
        );
        if (resolved && bindings.get(declarator.id.name) !== resolved) {
          bindings.set(declarator.id.name, resolved);
          changed = true;
        }
      }
      if (
        declarator.id.type === 'ObjectPattern' &&
        initializer?.type === 'Identifier' &&
        namespaces.has(initializer.name)
      ) {
        for (const property of declarator.id.properties) {
          if (
            property.type !== 'Property' ||
            property.value.type !== 'Identifier'
          ) {
            continue;
          }
          const name = routingStaticKeyName(property, staticStrings);
          if (name && bindings.get(property.value.name) !== name) {
            bindings.set(property.value.name, name);
            changed = true;
          }
        }
      }
    }
  }

  return { bindings, namespaces, staticStrings };
}

function routingBindingName(
  node,
  bindings,
  namespaces,
  staticStrings = new Map(),
) {
  const expression = routingUnwrap(node);
  if (!expression) {
    return null;
  }
  if (expression.type === 'Identifier') {
    return bindings.get(expression.name) ?? null;
  }
  if (
    expression.type === 'MemberExpression' &&
    routingUnwrap(expression.object)?.type === 'Identifier' &&
    namespaces.has(routingUnwrap(expression.object).name)
  ) {
    return routingStaticPropertyName(expression, staticStrings);
  }
  return null;
}

function jsxRoutingBindingName(name, bindings, namespaces) {
  if (name.type === 'JSXIdentifier') {
    return bindings.get(name.name) ?? null;
  }
  if (
    name.type === 'JSXMemberExpression' &&
    name.object.type === 'JSXIdentifier' &&
    namespaces.has(name.object.name) &&
    name.property.type === 'JSXIdentifier'
  ) {
    return name.property.name;
  }
  return null;
}


function objectPropertyMap(node, staticStrings = new Map()) {
  const properties = new Map();
  if (node.type !== 'ObjectExpression') {
    return properties;
  }
  for (const property of node.properties) {
    if (property.type !== 'Property') {
      continue;
    }
    const name = routingStaticKeyName(property, staticStrings);
    if (name) {
      properties.set(name.toLowerCase(), property.value);
    }
  }
  return properties;
}

function staticRouteString(node, staticStrings = new Map()) {
  const value = routingStaticString(node, staticStrings);
  return value?.startsWith('/') && !value.startsWith('/api/')
    ? value
    : null;
}

function initializerOwnsRouteGraph(initializer) {
  let ownsGraph = false;
  walkRoutingSyntax(initializer, (node) => {
    if (ownsGraph || node.type !== 'ObjectExpression') {
      return;
    }
    const properties = objectPropertyMap(node);
    const path = staticRouteString(properties.get('path'));
    if (
      (path &&
        ['children', 'element', 'id', 'index', 'loader'].some((key) =>
          properties.has(key),
        )) ||
      (properties.get('index')?.type === 'Literal' &&
        properties.get('index').value === true &&
        properties.has('element'))
    ) {
      ownsGraph = true;
    }
  });
  return ownsGraph;
}

const NAVIGATION_OWNERSHIP_KEYS = new Set([
  'end',
  'exact',
  'group',
  'match',
  'order',
  'permission',
  'role',
  'visible',
]);

const ROUTE_METADATA_SOFT_KEYS = new Set(['title']);
const ROUTE_METADATA_HARD_KEYS = new Set([
  'accessibility',
  'accessibilitytitle',
  'accessibilitytitlekey',
  'allowbypass',
  'bypass',
  'canbypass',
  'documenttitle',
  'documenttitlekey',
  'titlekey',
  'focus',
  'focusselector',
  'focustarget',
  'interruption',
  'interruptible',
  'navigationinterruption',
]);
const ROUTE_METADATA_OWNERSHIP_KEYS = new Set([
  ...ROUTE_METADATA_HARD_KEYS,
  ...ROUTE_METADATA_SOFT_KEYS,
]);

function createRoutingLiteralResolver(ast, staticStrings) {
  const initializers = new Map();
  walkRoutingSyntax(ast, (node) => {
    if (
      node.type === 'VariableDeclarator' &&
      node.id.type === 'Identifier' &&
      node.init
    ) {
      initializers.set(node.id.name, node.init);
    }
  });

  function resolve(node, seen = new Set()) {
    const expression = unwrapTransparentCall(node);
    if (
      expression?.type !== 'Identifier' ||
      seen.has(expression.name) ||
      !initializers.has(expression.name)
    ) {
      return expression;
    }
    const nextSeen = new Set(seen);
    nextSeen.add(expression.name);
    return resolve(initializers.get(expression.name), nextSeen);
  }

  function arrayElements(node, seen = new Set()) {
    const expression = resolve(node, seen);
    if (expression?.type === 'NewExpression') {
      const constructor = routingUnwrap(expression.callee);
      if (
        constructor?.type === 'Identifier' &&
        constructor.name === 'Set' &&
        expression.arguments[0]
      ) {
        return arrayElements(expression.arguments[0], seen);
      }
    }
    if (expression?.type !== 'ArrayExpression') {
      return null;
    }
    const elements = [];
    for (const element of expression.elements) {
      if (!element) continue;
      if (element.type === 'SpreadElement') {
        const spreadElements = arrayElements(element.argument, seen);
        if (spreadElements) {
          elements.push(...spreadElements);
        }
      } else {
        elements.push(resolve(element, seen));
      }
    }
    return elements;
  }

  function objectProperties(node, seen = new Set()) {
    const expression = resolve(node, seen);
    if (expression?.type !== 'ObjectExpression') {
      return null;
    }
    const properties = [];
    for (const property of expression.properties) {
      if (property.type === 'SpreadElement') {
        const spreadProperties = objectProperties(property.argument, seen);
        if (spreadProperties) {
          properties.push(...spreadProperties);
        }
      } else if (property.type === 'Property') {
        properties.push(property);
      }
    }
    return properties;
  }

  function propertyMap(node) {
    const properties = new Map();
    for (const property of objectProperties(node) ?? []) {
      const name = routingStaticKeyName(property, staticStrings);
      if (name) {
        properties.set(name.toLowerCase(), property.value);
      }
    }
    return properties;
  }

  return {
    arrayElements,
    objectProperties,
    propertyMap,
    resolve,
    staticStrings,
  };
}

function literalDestinationRegistryMetadata(initializer, resolver) {
  const expression = resolver.resolve(initializer);
  const resolvedElements = resolver.arrayElements(expression);
  if (resolvedElements) {
    const literalRoutes = resolvedElements
      .map((element) =>
        staticRouteString(element, resolver.staticStrings),
      )
      .filter(Boolean);
    if (
      literalRoutes.length > 0 &&
      literalRoutes.length === resolvedElements.length
    ) {
      return {
        kind: 'route-policy',
        ownsPolicyMetadata: false,
        pathCollection: true,
      };
    }

    const entries = resolvedElements.filter(
      (element) => resolver.resolve(element)?.type === 'ObjectExpression',
    );
    const routeMetadataEntries = entries.filter((entry) => {
      const properties = resolver.propertyMap(entry);
      const destination = ['destination', 'href', 'path', 'pathname', 'pattern', 'route', 'to']
        .map((key) =>
          staticRouteString(
            properties.get(key),
            resolver.staticStrings,
          ),
        )
        .find(Boolean);
      return (
        destination &&
        [...ROUTE_METADATA_OWNERSHIP_KEYS].some((key) =>
          properties.has(key),
        )
      );
    });
    if (routeMetadataEntries.length > 0) {
      const ownsHardPolicyMetadata = routeMetadataEntries.some((entry) => {
        const properties = resolver.propertyMap(entry);
        return [...ROUTE_METADATA_HARD_KEYS].some((key) => properties.has(key));
      });
      return {
        kind: 'route-metadata',
        ownsPolicyMetadata: true,
        ownsHardPolicyMetadata,
        pathCollection: false,
      };
    }

    const navigationEntries = entries.filter((entry) => {
      const properties = resolver.propertyMap(entry);
      const destination = ['destination', 'href', 'path', 'to']
        .map((key) =>
          staticRouteString(
            properties.get(key),
            resolver.staticStrings,
          ),
        )
        .find(Boolean);
      return (
        destination &&
        ['label', 'labelkey'].some((key) => properties.has(key))
      );
    });
    if (navigationEntries.length === 0) {
      return null;
    }
    return {
      kind: 'navigation',
      ownsPolicyMetadata: navigationEntries.some((entry) => {
        const properties = resolver.propertyMap(entry);
        return [...NAVIGATION_OWNERSHIP_KEYS].some((key) =>
          properties.has(key),
        );
      }),
      pathCollection: false,
    };
  }

  if (expression?.type === 'ArrayExpression') {
    return null;
  }

  const objectProperties = resolver.objectProperties(expression);
  if (objectProperties) {
    const redirectEntries = objectProperties.filter(
      (property) =>
        staticRouteString(property.key, resolver.staticStrings) &&
        staticRouteString(property.value, resolver.staticStrings),
    );
    if (redirectEntries.length > 0) {
      return {
        kind: 'redirect',
        ownsPolicyMetadata: true,
        pathCollection: false,
      };
    }

    const routeMetadataEntries = objectProperties.filter((property) => {
      if (
        !staticRouteString(property.key, resolver.staticStrings)
      ) {
        return false;
      }
      const value = resolver.resolve(property.value);
      if (
        routingStaticString(value, resolver.staticStrings) !== null ||
        value?.type === 'Literal' && typeof value.value === 'boolean'
      ) {
        return true;
      }
      const properties = resolver.propertyMap(value);
      return [...ROUTE_METADATA_OWNERSHIP_KEYS].some((key) =>
        properties.has(key),
      );
    });
    if (routeMetadataEntries.length > 0) {
      return {
        kind: 'route-metadata',
        ownsPolicyMetadata: true,
        ownsHardPolicyMetadata: true,
        pathCollection: false,
      };
    }
  }

  return null;
}

function registryBindingRendersNavigation(
  ast,
  bindingName,
  reactRouter,
) {
  let rendersNavigation = false;
  walkRoutingSyntax(ast, (node) => {
    if (
      rendersNavigation ||
      node.type !== 'CallExpression' ||
      routingUnwrap(node.callee)?.type !== 'MemberExpression'
    ) {
      return;
    }
    const callee = routingUnwrap(node.callee);
    const collection = routingUnwrap(callee.object);
    if (
      collection?.type !== 'Identifier' ||
      collection.name !== bindingName ||
      routingStaticPropertyName(
        callee,
        reactRouter.staticStrings,
      ) !== 'map'
    ) {
      return;
    }
    for (const argument of node.arguments) {
      walkRoutingSyntax(argument, (candidate) => {
        if (
          candidate.type === 'JSXOpeningElement' &&
          jsxRoutingBindingName(
            candidate.name,
            reactRouter.bindings,
            reactRouter.namespaces,
          ) === 'NavLink'
        ) {
          rendersNavigation = true;
        }
      });
    }
  });
  return rendersNavigation;
}

function registryBindingClassifiesRoute(
  ast,
  bindingName,
  reactRouter,
) {
  let classifiesRoute = false;
  walkRoutingSyntax(ast, (node) => {
    if (
      classifiesRoute ||
      node.type !== 'CallExpression' ||
      routingUnwrap(node.callee)?.type !== 'MemberExpression'
    ) {
      return;
    }
    const callee = routingUnwrap(node.callee);
    const collection = routingUnwrap(callee.object);
    if (
      collection?.type === 'Identifier' &&
      collection.name === bindingName &&
      ['has', 'includes'].includes(
        routingStaticPropertyName(
          callee,
          reactRouter.staticStrings,
        ),
      )
    ) {
      classifiesRoute = true;
    }
  });
  return classifiesRoute;
}

function registryBindingEscapes(
  ast,
  declarator,
  declaration,
  ancestors,
) {
  if (
    declarator.id.type !== 'Identifier' ||
    declaration?.type !== 'VariableDeclaration'
  ) {
    return false;
  }
  const bindingName = declarator.id.name;
  const containingFunction = [...ancestors]
    .reverse()
    .find((ancestor) => ROUTING_FUNCTION_TYPES.has(ancestor.type));

  if (!containingFunction) {
    return ast.body.some((statement) => {
      if (
        statement.type === 'ExportNamedDeclaration' &&
        statement.declaration === declaration
      ) {
        return true;
      }
      if (
        statement.type === 'ExportDefaultDeclaration' &&
        routingUnwrap(statement.declaration)?.type === 'Identifier'
      ) {
        return routingUnwrap(statement.declaration).name === bindingName;
      }
      return (
        statement.type === 'ExportNamedDeclaration' &&
        statement.specifiers.some(
          (specifier) =>
            specifier.local?.type === 'Identifier' &&
            specifier.local.name === bindingName,
        )
      );
    });
  }

  if (
    containingFunction.type === 'ArrowFunctionExpression' &&
    routingUnwrap(containingFunction.body)?.type === 'Identifier' &&
    routingUnwrap(containingFunction.body).name === bindingName
  ) {
    return true;
  }

  let returned = false;
  function inspectScope(node) {
    if (
      returned ||
      !node ||
      typeof node !== 'object' ||
      typeof node.type !== 'string'
    ) {
      return;
    }
    if (
      node !== containingFunction &&
      ROUTING_FUNCTION_TYPES.has(node.type)
    ) {
      return;
    }
    if (
      node.type === 'ReturnStatement' &&
      routingUnwrap(node.argument)?.type === 'Identifier' &&
      routingUnwrap(node.argument).name === bindingName
    ) {
      returned = true;
      return;
    }
    for (const [key, value] of Object.entries(node)) {
      if (['loc', 'parent', 'range', 'tokens'].includes(key)) {
        continue;
      }
      if (Array.isArray(value)) {
        value.forEach(inspectScope);
      } else {
        inspectScope(value);
      }
    }
  }
  inspectScope(containingFunction.body);
  return returned;
}

function findLiteralDestinationRegistries(ast, reactRouter) {
  const registries = [];
  const seenNodes = new Set();
  const resolver = createRoutingLiteralResolver(
    ast,
    reactRouter.staticStrings,
  );

  function addRegistry(node, reason, kind) {
    if (!seenNodes.has(node)) {
      seenNodes.add(node);
      registries.push({ node, reason, kind });
    }
  }

  walkRoutingSyntax(ast, (node, parent, ancestors) => {
    if (node.type === 'VariableDeclarator' && node.init) {
      const metadata = literalDestinationRegistryMetadata(
        node.init,
        resolver,
      );
      if (!metadata) {
        return;
      }
      const bindingName =
        node.id.type === 'Identifier' ? node.id.name : null;
      const escapes =
        bindingName !== null &&
        registryBindingEscapes(ast, node, parent, ancestors);
      const rendersNavigation =
        bindingName !== null &&
        registryBindingRendersNavigation(ast, bindingName, reactRouter);
      const classifiesRoute =
        bindingName !== null &&
        metadata.pathCollection &&
        registryBindingClassifiesRoute(ast, bindingName, reactRouter);
      const authoritativeMetadata =
        metadata.kind === 'route-metadata' &&
        (metadata.ownsHardPolicyMetadata || escapes || classifiesRoute);
      if (
        metadata.kind === 'redirect' ||
        authoritativeMetadata ||
        (metadata.kind !== 'route-metadata' && metadata.ownsPolicyMetadata) ||
        (bindingName !== null &&
          (escapes ||
            rendersNavigation ||
            classifiesRoute))
      ) {
        addRegistry(
          node,
          `${metadata.kind} registry owns route destinations`,
          metadata.kind,
        );
      }
      return;
    }

    const directExpression =
      node.type === 'ReturnStatement'
        ? node.argument
        : node.type === 'ExportDefaultDeclaration'
          ? node.declaration
          : null;
    const directMetadata =
      directExpression &&
      routingUnwrap(directExpression)?.type !== 'Identifier'
      ? literalDestinationRegistryMetadata(directExpression, resolver)
      : null;
    if (directMetadata) {
      addRegistry(
        node,
        'returned or exported destination registry',
        directMetadata.kind,
      );
    }

    const expressionMetadata =
      node.type === 'ArrowFunctionExpression' &&
      node.body.type !== 'BlockStatement' &&
      literalDestinationRegistryMetadata(node.body, resolver);
    if (expressionMetadata) {
      addRegistry(
        node,
        'returned destination registry',
        expressionMetadata.kind,
      );
    }
  });

  return registries;
}

function routeClassifierOperandKey(node, staticStrings) {
  const expression = routingUnwrap(node);
  if (expression?.type === 'Identifier') {
    return `identifier:${expression.name}`;
  }
  if (expression?.type !== 'MemberExpression') {
    return null;
  }
  const objectKey = routeClassifierOperandKey(
    expression.object,
    staticStrings,
  );
  const propertyKey = routingStaticPropertyName(
    expression,
    staticStrings,
  );
  return objectKey && propertyKey
    ? `${objectKey}.${propertyKey}`
    : null;
}

function findLiteralRouteClassifiers(ast, staticStrings) {
  const classifiers = [];
  walkRoutingSyntax(ast, (node) => {
    if (!ROUTING_FUNCTION_TYPES.has(node.type)) {
      return;
    }
    const routesByOperand = new Map();
    walkFunctionClosureSyntax(node, (candidate) => {
      if (
        candidate.type === 'BinaryExpression' &&
        ['==', '==='].includes(candidate.operator)
      ) {
        const leftRoute = staticRouteString(
          candidate.left,
          staticStrings,
        );
        const rightRoute = staticRouteString(
          candidate.right,
          staticStrings,
        );
        const operand = leftRoute
          ? candidate.right
          : rightRoute
            ? candidate.left
            : null;
        const route = leftRoute ?? rightRoute;
        const operandKey = operand
          ? routeClassifierOperandKey(operand, staticStrings)
          : null;
        if (!route || !operandKey) {
          return;
        }
        const routes = routesByOperand.get(operandKey) ?? new Set();
        routes.add(route);
        routesByOperand.set(operandKey, routes);
        return;
      }
      if (candidate.type !== 'SwitchStatement') {
        return;
      }
      const discriminant = unwrapTransparentCall(candidate.discriminant);
      let operandKey = routeClassifierOperandKey(
        discriminant,
        staticStrings,
      );
      if (
        !operandKey &&
        discriminant?.type === 'Identifier' &&
        staticStrings.has(discriminant.name)
      ) {
        // Aliased string discriminant is not a pathname classifier.
        return;
      }
      if (!operandKey && discriminant?.type === 'Identifier') {
        operandKey = `identifier:${discriminant.name}`;
      }
      if (!operandKey) {
        return;
      }
      const routes = routesByOperand.get(operandKey) ?? new Set();
      for (const switchCase of candidate.cases) {
        if (!switchCase.test) {
          continue;
        }
        const route = staticRouteString(switchCase.test, staticStrings);
        if (route) {
          routes.add(route);
        }
      }
      routesByOperand.set(operandKey, routes);
    });
    if (
      [...routesByOperand.values()].some((routes) => routes.size >= 2)
    ) {
      classifiers.push(node);
    }
  });
  return classifiers;
}

const ROUTING_FUNCTION_TYPES = new Set([
  'ArrowFunctionExpression',
  'FunctionDeclaration',
  'FunctionExpression',
]);

function walkFunctionClosureSyntax(functionNode, visitor) {
  walkRoutingSyntax(functionNode.body, visitor);
}


function addPatternBindings(pattern, bindings) {
  const target = routingUnwrap(pattern);
  if (!target) {
    return;
  }
  if (target.type === 'Identifier') {
    bindings.add(target.name);
    return;
  }
  if (target.type === 'ObjectPattern') {
    for (const property of target.properties) {
      if (property.type === 'Property') {
        addPatternBindings(property.value, bindings);
      } else if (property.type === 'RestElement') {
        addPatternBindings(property.argument, bindings);
      }
    }
    return;
  }
  if (target.type === 'ArrayPattern') {
    target.elements.forEach((element) =>
      addPatternBindings(element, bindings),
    );
  }
}

function syntaxUsesBinding(node, bindings) {
  let used = false;
  walkRoutingSyntax(node, (candidate) => {
    if (
      candidate.type === 'Identifier' &&
      bindings.has(candidate.name)
    ) {
      used = true;
    }
  });
  return used;
}

function syntaxContainsRouteTransition(
  node,
  reactRouter,
  navigatorBindings,
) {
  let found = false;
  walkRoutingSyntax(node, (candidate) => {
    if (found) {
      return;
    }
    if (candidate.type === 'JSXOpeningElement') {
      found =
        jsxRoutingBindingName(
          candidate.name,
          reactRouter.bindings,
          reactRouter.namespaces,
        ) === 'Navigate';
      return;
    }
    if (candidate.type !== 'CallExpression') {
      return;
    }
    const callee = routingUnwrap(candidate.callee);
    if (
      callee?.type === 'Identifier' &&
      navigatorBindings.has(callee.name)
    ) {
      found = true;
      return;
    }
    found =
      routingBindingName(
        candidate.callee,
        reactRouter.bindings,
        reactRouter.namespaces,
        reactRouter.staticStrings,
      ) === 'redirect';
  });
  return found;
}


function callExpressionIsAuthPolicyHook(callee, reactRouter) {
  const binding = routingBindingName(
    callee,
    reactRouter.bindings,
    reactRouter.namespaces,
    reactRouter.staticStrings,
  );
  if (binding && /^(?:use)?Auth|Session|Authentication/i.test(binding)) {
    return true;
  }
  const expression = routingUnwrap(callee);
  if (expression?.type === 'Identifier') {
    return /^(?:use)?Auth|useSession|useAuthentication|useAuthStore$/i.test(
      expression.name,
    );
  }
  return false;
}

function expressionHasAuthPolicySignal(node, authStateBindings) {
  let found = false;
  walkRoutingSyntax(node, (candidate) => {
    if (found) {
      return;
    }
    if (
      candidate.type === 'Identifier' &&
      authStateBindings.has(candidate.name)
    ) {
      found = true;
      return;
    }
    if (candidate.type === 'MemberExpression') {
      const property = routingStaticPropertyName(candidate);
      if (
        property &&
        /^(?:isAuthenticated|authenticated|roles|lifecycle)$/i.test(property)
      ) {
        found = true;
        return;
      }
    }
    const literal = routingStaticString(candidate);
    if (
      literal &&
      /ACCOUNT_NOT_|PROVISIONING|PENDING_VERIFICATION|ACTIVE|SUSPENDED/.test(
        literal,
      )
    ) {
      found = true;
    }
  });
  return found;
}

function findAuthenticationAwareRouteWrappers(ast, reactRouter) {
  const wrappers = [];

  walkRoutingSyntax(ast, (node) => {
    if (!ROUTING_FUNCTION_TYPES.has(node.type)) {
      return;
    }

    let rendersOutlet = false;
    const navigatorBindings = new Set();
    const stateBindings = new Set();
    const authStateBindings = new Set();

    walkFunctionClosureSyntax(node, (candidate) => {
      if (
        candidate.type === 'VariableDeclarator' &&
        routingUnwrap(candidate.init)?.type === 'CallExpression'
      ) {
        const initializer = routingUnwrap(candidate.init);
        const binding = routingBindingName(
          initializer.callee,
          reactRouter.bindings,
          reactRouter.namespaces,
          reactRouter.staticStrings,
        );
        if (binding === 'useNavigate') {
          addPatternBindings(candidate.id, navigatorBindings);
        } else if (binding !== 'useMatches') {
          addPatternBindings(candidate.id, stateBindings);
          if (callExpressionIsAuthPolicyHook(initializer.callee, reactRouter)) {
            addPatternBindings(candidate.id, authStateBindings);
          }
        }
      }
      if (candidate.type === 'JSXOpeningElement') {
        const binding = jsxRoutingBindingName(
          candidate.name,
          reactRouter.bindings,
          reactRouter.namespaces,
        );
        rendersOutlet ||= binding === 'Outlet';
      }
    });

    let changed = true;
    while (changed) {
      changed = false;
      walkFunctionClosureSyntax(node, (candidate) => {
        if (
          candidate.type === 'VariableDeclarator' &&
          candidate.id.type === 'Identifier' &&
          routingUnwrap(candidate.init)?.type === 'Identifier'
        ) {
          const sourceName = routingUnwrap(candidate.init).name;
          if (
            navigatorBindings.has(sourceName) &&
            !navigatorBindings.has(candidate.id.name)
          ) {
            navigatorBindings.add(candidate.id.name);
            changed = true;
          }
          if (
            stateBindings.has(sourceName) &&
            !stateBindings.has(candidate.id.name)
          ) {
            stateBindings.add(candidate.id.name);
            changed = true;
          }
        }
      });
    }

    let routesByState = false;
    walkFunctionClosureSyntax(node, (candidate) => {
      let stateExpression = null;
      let transitionExpression = null;
      if (candidate.type === 'IfStatement') {
        stateExpression = candidate.test;
        transitionExpression = {
          type: 'Program',
          body: [candidate.consequent, candidate.alternate].filter(Boolean),
        };
      } else if (candidate.type === 'ConditionalExpression') {
        stateExpression = candidate.test;
        transitionExpression = {
          type: 'ArrayExpression',
          elements: [candidate.consequent, candidate.alternate],
        };
      } else if (candidate.type === 'LogicalExpression') {
        stateExpression = candidate.left;
        transitionExpression = candidate.right;
      } else if (candidate.type === 'SwitchStatement') {
        stateExpression = candidate.discriminant;
        transitionExpression = {
          type: 'Program',
          body: candidate.cases.flatMap(
            (caseNode) => caseNode.consequent,
          ),
        };
      }
      routesByState ||=
        stateExpression !== null &&
        (syntaxUsesBinding(stateExpression, authStateBindings) ||
          (syntaxUsesBinding(stateExpression, stateBindings) &&
            expressionHasAuthPolicySignal(
              stateExpression,
              authStateBindings,
            ))) &&
        syntaxContainsRouteTransition(
          transitionExpression,
          reactRouter,
          navigatorBindings,
        );
    });

    if (rendersOutlet && routesByState) {
      wrappers.push(node);
    }
  });

  return wrappers;
}

function addRoutingViolation(violations, seen, violation) {
  const key = `${violation.line}:${violation.rule}:${violation.detail}`;
  if (!seen.has(key)) {
    seen.add(key);
    violations.push(violation);
  }
}

/** Enforces the frozen Web route graph, policy, router, and navigation owners. */
export function findWebRoutingOwnershipViolations(source, repositoryPath) {
  const normalizedPath = normalizeRepositoryPath(repositoryPath);
  const testOwned =
    normalizedPath.includes('/test/') ||
    /\.(?:test|spec)\.[jt]sx?$/.test(normalizedPath);
  if (testOwned) {
    return [];
  }

  const ast = routingSyntaxTree(source);
  const violations = [];
  const seen = new Set();
  const compilerOwner = 'apps/web/src/routing/create-app-router.tsx';
  const manifestOwner = 'apps/web/src/routing/route-manifest.ts';
  const providerOwner = 'apps/web/src/App.tsx';
  const policyOwner = 'apps/web/src/routing/RoutePolicyBoundary.tsx';
  const reactRouter = collectReactRouterBindings(ast);
  const resolver = createRoutingLiteralResolver(
    ast,
    reactRouter.staticStrings,
  );
  const factoryCounts = new Map();
  const providerNodes = [];

  walkRoutingSyntax(ast, (node) => {
    if (
      node.type === 'ImportDeclaration' &&
      LEGACY_ROUTING_IMPORTS.some((pattern) => pattern.test(node.source.value))
    ) {
      addRoutingViolation(violations, seen, {
        line: routingLine(node),
        rule: 'no-legacy-routing-owner',
        detail: `legacy routing import '${node.source.value}' is retired`,
      });
    }

    if (node.type === 'CallExpression') {
      const binding = routingBindingName(
        node.callee,
        reactRouter.bindings,
        reactRouter.namespaces,
        reactRouter.staticStrings,
      );
      if (ROUTER_FACTORY_EXPORTS.has(binding)) {
        if (normalizedPath !== compilerOwner) {
          addRoutingViolation(violations, seen, {
            line: routingLine(node),
            rule: 'single-router-factory-owner',
            detail: `router factory '${binding}' is owned by routing/create-app-router.tsx`,
          });
        } else {
          factoryCounts.set(binding, (factoryCounts.get(binding) ?? 0) + 1);
        }
      }
      if (
        ['createRoutesFromElements', 'useRoutes'].includes(binding) &&
        normalizedPath !== compilerOwner
      ) {
        addRoutingViolation(violations, seen, {
          line: routingLine(node),
          rule: 'single-route-graph-owner',
          detail: `route graph API '${binding}' is owned by the canonical compiler`,
        });
      }
    }

    if (node.type === 'JSXOpeningElement') {
      const binding = jsxRoutingBindingName(
        node.name,
        reactRouter.bindings,
        reactRouter.namespaces,
      );
      if (binding === ROUTER_PROVIDER_EXPORT) {
        if (normalizedPath !== providerOwner) {
          addRoutingViolation(violations, seen, {
            line: routingLine(node),
            rule: 'single-router-provider-owner',
            detail: 'production RouterProvider ownership belongs only to App.tsx',
          });
        } else {
          providerNodes.push(node);
        }
      }
      if (
        ROUTE_GRAPH_EXPORTS.has(binding) &&
        normalizedPath !== compilerOwner
      ) {
        addRoutingViolation(violations, seen, {
          line: routingLine(node),
          rule: 'single-route-graph-owner',
          detail: `production <${binding}> graphs are retired`,
        });
      }
    }
  });

  for (const [factoryName, count] of factoryCounts) {
    if (count > 1) {
      addRoutingViolation(violations, seen, {
        line: 1,
        rule: 'single-router-factory-owner',
        detail: `canonical compiler may own at most one '${factoryName}' call`,
      });
    }
  }
  for (const node of providerNodes.slice(1)) {
    addRoutingViolation(violations, seen, {
      line: routingLine(node),
      rule: 'single-router-provider-owner',
      detail: 'App.tsx may own at most one production RouterProvider',
    });
  }

  if (normalizedPath !== manifestOwner && normalizedPath !== compilerOwner) {
    const graphInitializers = new Map();
    walkRoutingSyntax(ast, (node) => {
      if (
        node.type === 'VariableDeclarator' &&
        node.id.type === 'Identifier' &&
        node.init
      ) {
        graphInitializers.set(node.id.name, node.init);
        if (initializerOwnsRouteGraph(node.init)) {
          addRoutingViolation(violations, seen, {
            line: routingLine(node),
            rule: 'single-route-graph-owner',
            detail:
              'route graphs must derive from the canonical manifest (including function-local declarations)',
          });
        }
      }
      if (node.type === 'ExportDefaultDeclaration' && node.declaration) {
        const declaration = unwrapTransparentCall(node.declaration);
        let owns = initializerOwnsRouteGraph(declaration);
        if (
          !owns &&
          declaration?.type === 'Identifier' &&
          graphInitializers.has(declaration.name)
        ) {
          owns = initializerOwnsRouteGraph(
            graphInitializers.get(declaration.name),
          );
        }
        if (owns) {
          addRoutingViolation(violations, seen, {
            line: routingLine(node),
            rule: 'single-route-graph-owner',
            detail: 'default-exported route graphs are forbidden',
          });
        }
      }
    });

    for (const registry of findLiteralDestinationRegistries(
      ast,
      reactRouter,
    )) {
      addRoutingViolation(violations, seen, {
        line: routingLine(registry.node),
        rule: ['route-metadata', 'route-policy'].includes(
          registry.kind,
        )
          ? 'manifest-owned-route-metadata'
          : 'manifest-owned-navigation',
        detail: `literal ${registry.reason} must derive from the canonical manifest`,
      });
    }

    for (const classifier of findLiteralRouteClassifiers(
      ast,
      reactRouter.staticStrings,
    )) {
      addRoutingViolation(violations, seen, {
        line: routingLine(classifier),
        rule: 'manifest-owned-route-metadata',
        detail:
          'literal pathname policy classification must derive from the canonical manifest',
      });
    }
  }

  const wrappers = findAuthenticationAwareRouteWrappers(ast, reactRouter);
  const wrappersToFlag =
    normalizedPath === policyOwner ? wrappers.slice(1) : wrappers;
  for (const wrapper of wrappersToFlag) {
    addRoutingViolation(violations, seen, {
      line: routingLine(wrapper),
      rule: 'single-route-policy-owner',
      detail:
        'RoutePolicyBoundary.tsx is the sole authentication-aware routing policy owner',
    });
  }

  void resolver;
  return violations;
}

/** Sprint 2.4 Web ownership rules that require both source and repository path context. */
export function findWebRuntimeOwnershipViolations(source, repositoryPath) {
  const violations = [];
  const normalizedPath = normalizeRepositoryPath(repositoryPath);
  const tokens = tokenizeModuleSyntax(source);

  for (const moduleImport of extractModuleSpecifiers(source)) {
    if (moduleImport.specifier === '@/api') {
      violations.push({
        line: moduleImport.line,
        rule: 'web-sdk-dependency-injection',
        detail: 'Web features must obtain domain clients from AppRuntimeProvider',
      });
    }
  }

  if (normalizedPath !== 'apps/web/src/app/sdk.ts') {
    for (const token of tokens) {
      if (token.kind === 'identifier' && SDK_COMPOSITION_FACTORIES.has(token.value)) {
        violations.push({
          line: token.line,
          rule: 'single-sdk-composition-owner',
          detail: `SDK factory '${token.value}' is owned by apps/web/src/app/sdk.ts`,
        });
      }
    }
  }

  const queryClientOwner = normalizedPath === 'apps/web/src/providers/query-client.ts';
  const testOwned = normalizedPath.includes('/test/') || /\.(?:test|spec)\.[jt]sx?$/.test(normalizedPath);
  if (!testOwned) {
    violations.push(...findWebAuthOwnershipAstViolations(source, normalizedPath));

    for (const token of tokens) {
      const allowedOwners = WEB_COMPOSITION_FACTORY_OWNERS.get(token.value);
      if (
        token.kind === 'identifier' &&
        allowedOwners &&
        !allowedOwners.has(normalizedPath)
      ) {
        violations.push({
          line: token.line,
          rule: 'single-web-composition-owner',
          detail: `Web composition factory '${token.value}' is not owned by ${normalizedPath}`,
        });
      }
    }
  }

  if (!queryClientOwner && !testOwned) {
    for (const line of queryClientConstructionLines(tokens)) {
      violations.push({
        line,
        rule: 'single-query-client-composition-owner',
        detail: 'production QueryClient construction is owned by providers/query-client.ts',
      });
    }
  }

  return violations;
}

export function extractPublicExports(source) {
  const exports = [];
  const tokens = tokenizeModuleSyntax(source);
  let braceDepth = 0;

  function addExport(name, kind, sourceModule = '<entrypoint>') {
    if (name) {
      exports.push({ name, kind, source: sourceModule });
    }
  }

  function sourceAfter(cursor) {
    if (tokens[cursor]?.value === 'from' && tokens[cursor + 1]?.kind === 'string') {
      return tokens[cursor + 1].value;
    }
    return '<entrypoint>';
  }

  for (let index = 0; index < tokens.length; index += 1) {
    const token = tokens[index];
    if (token.value === '{') {
      braceDepth += 1;
      continue;
    }
    if (token.value === '}') {
      braceDepth -= 1;
      continue;
    }
    if (braceDepth !== 0 || token.kind !== 'identifier' || token.value !== 'export') {
      continue;
    }

    let cursor = index + 1;
    if (tokens[cursor]?.value === 'default') {
      addExport('default', 'value');
      continue;
    }

    let outerTypeOnly = false;
    if (
      tokens[cursor]?.value === 'type' &&
      (tokens[cursor + 1]?.value === '{' || tokens[cursor + 1]?.value === '*')
    ) {
      outerTypeOnly = true;
      cursor += 1;
    }

    if (tokens[cursor]?.value === '{') {
      cursor += 1;
      const entries = [];
      while (cursor < tokens.length && tokens[cursor].value !== '}') {
        let entryTypeOnly = outerTypeOnly;
        if (
          tokens[cursor].value === 'type' &&
          ![',', 'as', '}'].includes(tokens[cursor + 1]?.value)
        ) {
          entryTypeOnly = true;
          cursor += 1;
        }

        const originalName = tokens[cursor];
        if (originalName?.kind !== 'identifier' && originalName?.kind !== 'string') {
          cursor += 1;
          continue;
        }
        cursor += 1;

        let exportedName = originalName.value;
        if (tokens[cursor]?.value === 'as') {
          const alias = tokens[cursor + 1];
          if (alias?.kind === 'identifier' || alias?.kind === 'string') {
            exportedName = alias.value;
          }
          cursor += 2;
        }
        entries.push({ name: exportedName, kind: entryTypeOnly ? 'type' : 'value' });

        if (tokens[cursor]?.value === ',') {
          cursor += 1;
        }
      }

      const sourceModule = sourceAfter(cursor + 1);
      for (const entry of entries) {
        addExport(entry.name, entry.kind, sourceModule);
      }
      continue;
    }

    if (tokens[cursor]?.value === '*') {
      cursor += 1;
      if (tokens[cursor]?.value === 'as') {
        addExport(
          tokens[cursor + 1]?.value,
          outerTypeOnly ? 'type' : 'value',
          sourceAfter(cursor + 2),
        );
      } else {
        addExport('*', outerTypeOnly ? 'type' : 'wildcard', sourceAfter(cursor));
      }
      continue;
    }

    while (['abstract', 'async', 'declare'].includes(tokens[cursor]?.value)) {
      cursor += 1;
    }

    const declarationKind = tokens[cursor]?.value;
    if (
      ![
        'class',
        'const',
        'enum',
        'function',
        'interface',
        'let',
        'module',
        'namespace',
        'type',
        'var',
      ].includes(declarationKind)
    ) {
      continue;
    }

    cursor += 1;
    if (declarationKind === 'function' && tokens[cursor]?.value === '*') {
      cursor += 1;
    }
    addExport(
      tokens[cursor]?.value,
      declarationKind === 'interface' || declarationKind === 'type' ? 'type' : 'value',
    );
  }

  return [
    ...new Map(
      exports.map((entry) => [`${entry.name}:${entry.kind}:${entry.source}`, entry]),
    ).values(),
  ].sort((left, right) => {
    const leftKey = `${left.name}:${left.kind}:${left.source}`;
    const rightKey = `${right.name}:${right.kind}:${right.source}`;
    return leftKey < rightKey ? -1 : leftKey > rightKey ? 1 : 0;
  });
}

export async function listSourceFiles(rootDirectory) {
  const files = [];

  async function visit(directory) {
    let entries;
    try {
      entries = await readdir(directory, { withFileTypes: true });
    } catch (error) {
      if (error?.code === 'ENOENT') {
        return;
      }
      throw error;
    }

    for (const entry of entries) {
      if (entry.isDirectory()) {
        if (!SKIPPED_DIRECTORIES.has(entry.name)) {
          await visit(path.join(directory, entry.name));
        }
      } else if (entry.isFile() && SOURCE_EXTENSIONS.has(path.extname(entry.name))) {
        files.push(path.join(directory, entry.name));
      }
    }
  }

  await visit(rootDirectory);
  return files.sort();
}

export function normalizeRepositoryPath(filePath) {
  return filePath.replaceAll('\\', '/').replace(/^\.\//, '');
}

export function isBackendProtectedPath(filePath) {
  const normalized = normalizeRepositoryPath(filePath);
  const rootBackendFiles = new Set([
    '.dockerignore',
    'PARKIO-API-REFERENCE.md',
    'build.gradle.kts',
    'gradle.properties',
    'settings.gradle.kts',
  ]);

  return (
    rootBackendFiles.has(normalized) ||
    /^(?:buildSrc|gradle|infra|platform|services)\//.test(normalized) ||
    /^docker(?:\/|-compose(?:\.|-))/.test(normalized) ||
    normalized === 'docs/architecture/openapi.md'
  );
}

export async function measureJavaScriptBundle(targetPath) {
  const targetStats = await stat(targetPath);
  const candidates = [];

  async function collect(candidatePath) {
    const candidateStats = await stat(candidatePath);
    if (candidateStats.isDirectory()) {
      const entries = await readdir(candidatePath);
      for (const entry of entries.sort()) {
        await collect(path.join(candidatePath, entry));
      }
    } else if (['.cjs', '.js', '.mjs'].includes(path.extname(candidatePath))) {
      candidates.push(candidatePath);
    }
  }

  if (targetStats.isDirectory()) {
    await collect(targetPath);
  } else {
    candidates.push(targetPath);
  }

  const files = [];
  for (const candidate of candidates) {
    const contents = await readFile(candidate);
    files.push({
      path: normalizeRepositoryPath(path.relative(targetPath, candidate) || path.basename(candidate)),
      rawBytes: contents.byteLength,
      gzipBytes: gzipSync(contents, { level: 9 }).byteLength,
    });
  }

  return {
    files,
    totals: files.reduce(
      (totals, file) => ({
        rawBytes: totals.rawBytes + file.rawBytes,
        gzipBytes: totals.gzipBytes + file.gzipBytes,
      }),
      { rawBytes: 0, gzipBytes: 0 },
    ),
  };
}
