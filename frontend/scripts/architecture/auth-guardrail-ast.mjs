import { parse } from '@typescript-eslint/typescript-estree';

const CREDENTIAL_NAMES = new Set([
  'accesstoken',
  'authtoken',
  'authorization',
  'bearer',
  'credential',
  'credentials',
  'refreshtoken',
  'sessiontoken',
]);
const CROSS_TAB_SENSITIVE_NAMES = new Set([
  ...CREDENTIAL_NAMES,
  'backendpayload',
  'coordinates',
  'email',
  'identity',
  'payload',
  'pii',
  'role',
  'roles',
  'user',
  'userobject',
]);
const STORAGE_OWNER_NAMES = new Set([
  'caches',
  'cachestorage',
  'indexeddb',
  'localstorage',
  'sessionstorage',
]);
const STORAGE_WRITE_METHODS = new Set(['add', 'open', 'put', 'set', 'setitem']);
const SAFE_CROSS_TAB_FIELDS = new Set(['eventid', 'type', 'version']);
const AUTH_IDENTITY_FIELDS = new Set([
  'accesstoken',
  'identity',
  'isauthenticated',
  'roles',
  'sessionepoch',
  'status',
  'user',
]);

function syntaxTree(source) {
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

function unwrap(node) {
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

function walk(node, visitor, ancestors = []) {
  if (!node || typeof node !== 'object' || typeof node.type !== 'string') {
    return;
  }
  visitor(node, ancestors);
  const nextAncestors = [...ancestors, node];
  for (const [key, value] of Object.entries(node)) {
    if (['loc', 'parent', 'range', 'tokens'].includes(key)) {
      continue;
    }
    if (Array.isArray(value)) {
      value.forEach((child) => walk(child, visitor, nextAncestors));
    } else {
      walk(value, visitor, nextAncestors);
    }
  }
}

function lineOf(node) {
  return node?.loc?.start?.line ?? 1;
}

function normalizedName(value) {
  return typeof value === 'string' ? value.replace(/[^A-Za-z0-9]/g, '').toLowerCase() : '';
}

function isSensitiveName(value, names) {
  const normalized = normalizedName(value);
  return (
    names.has(normalized) ||
    [...names].some((sensitiveName) => normalized.includes(sensitiveName))
  );
}

function staticPropertyName(node) {
  const property = unwrap(node?.property);
  if (!property) {
    return null;
  }
  if (!node.computed && (property.type === 'Identifier' || property.type === 'PrivateIdentifier')) {
    return property.name;
  }
  if (property.type === 'Literal' && typeof property.value === 'string') {
    return property.value;
  }
  if (
    property.type === 'TemplateLiteral' &&
    property.expressions.length === 0 &&
    property.quasis.length === 1
  ) {
    return property.quasis[0]?.value?.cooked ?? null;
  }
  return null;
}

function staticKeyName(node) {
  const key = unwrap(node?.key);
  if (!key) {
    return null;
  }
  if (!node.computed && (key.type === 'Identifier' || key.type === 'PrivateIdentifier')) {
    return key.name;
  }
  if (key.type === 'Literal' && typeof key.value === 'string') {
    return key.value;
  }
  return null;
}

function memberPath(node) {
  const expression = unwrap(node);
  if (!expression) {
    return [];
  }
  if (expression.type === 'Identifier') {
    return [expression.name];
  }
  if (expression.type === 'ThisExpression') {
    return ['this'];
  }
  if (expression.type !== 'MemberExpression') {
    return [];
  }
  const propertyName = staticPropertyName(expression);
  if (!propertyName) {
    return [];
  }
  return [...memberPath(expression.object), propertyName];
}

function expressionContainsSensitiveValue(node, sensitiveAliases, names = CREDENTIAL_NAMES) {
  let sensitive = false;
  walk(node, (current) => {
    if (sensitive) {
      return;
    }
    if (
      current.type === 'Identifier' &&
      (sensitiveAliases.has(current.name) || isSensitiveName(current.name, names))
    ) {
      sensitive = true;
      return;
    }
    if (current.type === 'Literal' && isSensitiveName(current.value, names)) {
      sensitive = true;
    }
    if (
      (current.type === 'MemberExpression' || current.type === 'Property') &&
      isSensitiveName(
        current.type === 'MemberExpression'
          ? staticPropertyName(current)
          : staticKeyName(current),
        names,
      )
    ) {
      sensitive = true;
    }
  });
  return sensitive;
}

function collectSensitiveAliases(ast, names = CREDENTIAL_NAMES) {
  const assignments = [];
  walk(ast, (node) => {
    if (
      node.type === 'VariableDeclarator' &&
      node.id.type === 'Identifier' &&
      node.init
    ) {
      assignments.push({ name: node.id.name, value: node.init });
    }
    if (
      node.type === 'AssignmentExpression' &&
      node.operator === '=' &&
      node.left.type === 'Identifier'
    ) {
      assignments.push({ name: node.left.name, value: node.right });
    }
  });

  const aliases = new Set();
  let changed = true;
  while (changed) {
    changed = false;
    for (const assignment of assignments) {
      if (
        !aliases.has(assignment.name) &&
        expressionContainsSensitiveValue(assignment.value, aliases, names)
      ) {
        aliases.add(assignment.name);
        changed = true;
      }
    }
  }
  return aliases;
}

function addViolation(violations, seen, violation) {
  const key = `${violation.line}:${violation.rule}:${violation.detail}`;
  if (seen.has(key)) {
    return;
  }
  seen.add(key);
  violations.push(violation);
}

function isStorageOwnerExpression(node, ownerAliases) {
  const expression = unwrap(node);
  if (!expression) {
    return false;
  }
  if (expression.type === 'Identifier') {
    return (
      ownerAliases.has(expression.name) ||
      STORAGE_OWNER_NAMES.has(normalizedName(expression.name))
    );
  }
  if (expression.type !== 'MemberExpression') {
    return false;
  }
  const propertyName = normalizedName(staticPropertyName(expression));
  if (!STORAGE_OWNER_NAMES.has(propertyName)) {
    return false;
  }
  const objectPath = memberPath(expression.object).map(normalizedName);
  return (
    objectPath.length === 0 ||
    ['globalthis', 'self', 'window'].includes(objectPath.at(-1))
  );
}

function storageMethodFromExpression(node, ownerAliases) {
  const expression = unwrap(node);
  if (!expression) {
    return null;
  }
  if (expression.type === 'MemberExpression') {
    const method = normalizedName(staticPropertyName(expression));
    if (STORAGE_WRITE_METHODS.has(method) && isStorageOwnerExpression(expression.object, ownerAliases)) {
      return method;
    }
  }
  if (
    expression.type === 'CallExpression' &&
    unwrap(expression.callee)?.type === 'MemberExpression' &&
    normalizedName(staticPropertyName(unwrap(expression.callee))) === 'bind'
  ) {
    return storageMethodFromExpression(unwrap(expression.callee).object, ownerAliases);
  }
  return null;
}

function collectStorageAliases(ast) {
  const ownerAliases = new Set();
  const methodAliases = new Map();
  const declarators = [];
  const assignments = [];
  walk(ast, (node) => {
    if (node.type === 'VariableDeclarator' && node.init) {
      declarators.push(node);
    }
    if (node.type === 'AssignmentExpression' && node.operator === '=') {
      assignments.push(node);
    }
  });

  let changed = true;
  while (changed) {
    changed = false;
    for (const declarator of declarators) {
      if (declarator.id.type === 'Identifier') {
        if (
          !ownerAliases.has(declarator.id.name) &&
          isStorageOwnerExpression(declarator.init, ownerAliases)
        ) {
          ownerAliases.add(declarator.id.name);
          changed = true;
        }
        const method = storageMethodFromExpression(declarator.init, ownerAliases);
        if (method && methodAliases.get(declarator.id.name) !== method) {
          methodAliases.set(declarator.id.name, method);
          changed = true;
        }
      }
      if (
        declarator.id.type === 'ObjectPattern' &&
        isStorageOwnerExpression(declarator.init, ownerAliases)
      ) {
        for (const property of declarator.id.properties) {
          if (
            property.type !== 'Property' ||
            property.value.type !== 'Identifier'
          ) {
            continue;
          }
          const method = normalizedName(staticKeyName(property));
          if (
            STORAGE_WRITE_METHODS.has(method) &&
            methodAliases.get(property.value.name) !== method
          ) {
            methodAliases.set(property.value.name, method);
            changed = true;
          }
        }
      }
      if (
        declarator.id.type === 'ObjectPattern' &&
        memberPath(declarator.init).map(normalizedName).at(-1) === 'window'
      ) {
        for (const property of declarator.id.properties) {
          if (
            property.type !== 'Property' ||
            property.value.type !== 'Identifier' ||
            !STORAGE_OWNER_NAMES.has(normalizedName(staticKeyName(property)))
          ) {
            continue;
          }
          if (!ownerAliases.has(property.value.name)) {
            ownerAliases.add(property.value.name);
            changed = true;
          }
        }
      }
    }
    for (const assignment of assignments) {
      if (assignment.left.type !== 'Identifier') {
        continue;
      }
      if (
        !ownerAliases.has(assignment.left.name) &&
        isStorageOwnerExpression(assignment.right, ownerAliases)
      ) {
        ownerAliases.add(assignment.left.name);
        changed = true;
      }
      const method = storageMethodFromExpression(assignment.right, ownerAliases);
      if (method && methodAliases.get(assignment.left.name) !== method) {
        methodAliases.set(assignment.left.name, method);
        changed = true;
      }
    }
  }
  return { methodAliases, ownerAliases };
}

function storageWriteCall(node, storageAliases) {
  if (node.type !== 'CallExpression') {
    return null;
  }
  const callee = unwrap(node.callee);
  if (callee?.type === 'Identifier' && storageAliases.methodAliases.has(callee.name)) {
    return storageAliases.methodAliases.get(callee.name);
  }
  return storageMethodFromExpression(callee, storageAliases.ownerAliases);
}

function functionBindings(ast) {
  const bindings = new Map();
  walk(ast, (node) => {
    if (node.type === 'FunctionDeclaration' && node.id) {
      bindings.set(node.id.name, node);
    }
    if (
      node.type === 'VariableDeclarator' &&
      node.id.type === 'Identifier' &&
      node.init &&
      ['ArrowFunctionExpression', 'FunctionExpression'].includes(unwrap(node.init)?.type)
    ) {
      bindings.set(node.id.name, unwrap(node.init));
    }
  });
  return bindings;
}

export function findCredentialPersistenceAstViolations(source) {
  const ast = syntaxTree(source);
  const violations = [];
  const seen = new Set();
  const sensitiveAliases = collectSensitiveAliases(ast);
  const storageAliases = collectStorageAliases(ast);
  const wrappers = new Map();

  for (const [name, fn] of functionBindings(ast)) {
    const sensitiveParameterIndexes = new Set();
    walk(fn.body, (node) => {
      if (!storageWriteCall(node, storageAliases)) {
        return;
      }
      fn.params.forEach((parameter, index) => {
        if (
          parameter.type === 'Identifier' &&
          node.arguments.some((argument) => {
            let usesParameter = false;
            walk(argument, (current) => {
              if (current.type === 'Identifier' && current.name === parameter.name) {
                usesParameter = true;
              }
            });
            return usesParameter;
          })
        ) {
          sensitiveParameterIndexes.add(index);
        }
      });
    });
    if (sensitiveParameterIndexes.size > 0) {
      wrappers.set(name, sensitiveParameterIndexes);
    }
  }

  walk(ast, (node) => {
    if (storageWriteCall(node, storageAliases)) {
      if (
        node.arguments.some((argument) =>
          expressionContainsSensitiveValue(argument, sensitiveAliases),
        )
      ) {
        addViolation(violations, seen, {
          line: lineOf(node),
          rule: 'no-browser-credential-persistence',
          detail: 'authentication credentials must remain memory-only',
        });
      }
      return;
    }

    if (node.type !== 'CallExpression' || unwrap(node.callee)?.type !== 'Identifier') {
      return;
    }
    const parameterIndexes = wrappers.get(unwrap(node.callee).name);
    if (
      parameterIndexes &&
      [...parameterIndexes].some((index) =>
        expressionContainsSensitiveValue(node.arguments[index], sensitiveAliases),
      )
    ) {
      addViolation(violations, seen, {
        line: lineOf(node),
        rule: 'no-browser-credential-persistence',
        detail: 'authentication credentials must remain memory-only',
      });
    }
  });

  return violations;
}

function isBroadcastChannelConstructor(node, aliases) {
  const expression = unwrap(node);
  if (!expression) {
    return false;
  }
  if (expression.type === 'Identifier') {
    return expression.name === 'BroadcastChannel' || aliases.has(expression.name);
  }
  return (
    expression.type === 'MemberExpression' &&
    staticPropertyName(expression) === 'BroadcastChannel'
  );
}

function collectBroadcastAliases(ast) {
  const constructorAliases = new Set();
  const postMessageAliases = new Set();
  const declarators = [];
  walk(ast, (node) => {
    if (node.type === 'VariableDeclarator' && node.init) {
      declarators.push(node);
    }
  });
  let changed = true;
  while (changed) {
    changed = false;
    for (const declarator of declarators) {
      if (declarator.id.type === 'Identifier') {
        if (
          !constructorAliases.has(declarator.id.name) &&
          isBroadcastChannelConstructor(declarator.init, constructorAliases)
        ) {
          constructorAliases.add(declarator.id.name);
          changed = true;
        }
        const expression = unwrap(declarator.init);
        const directPostMessage =
          expression?.type === 'MemberExpression' &&
          normalizedName(staticPropertyName(expression)) === 'postmessage';
        const boundPostMessage =
          expression?.type === 'CallExpression' &&
          unwrap(expression.callee)?.type === 'MemberExpression' &&
          normalizedName(staticPropertyName(unwrap(expression.callee))) === 'bind' &&
          normalizedName(
            staticPropertyName(unwrap(expression.callee).object),
          ) === 'postmessage';
        if (
          (directPostMessage || boundPostMessage) &&
          !postMessageAliases.has(declarator.id.name)
        ) {
          postMessageAliases.add(declarator.id.name);
          changed = true;
        }
      }
      if (declarator.id.type === 'ObjectPattern') {
        for (const property of declarator.id.properties) {
          if (
            property.type === 'Property' &&
            property.value.type === 'Identifier' &&
            normalizedName(staticKeyName(property)) === 'postmessage' &&
            !postMessageAliases.has(property.value.name)
          ) {
            postMessageAliases.add(property.value.name);
            changed = true;
          }
        }
      }
    }
  }
  return { constructorAliases, postMessageAliases };
}

function collectObjectBindings(ast) {
  const bindings = new Map();
  walk(ast, (node) => {
    if (
      node.type === 'VariableDeclarator' &&
      node.id.type === 'Identifier' &&
      unwrap(node.init)?.type === 'ObjectExpression'
    ) {
      bindings.set(node.id.name, unwrap(node.init));
    }
  });
  return bindings;
}

function inspectCrossTabObject(
  objectExpression,
  objectBindings,
  sensitiveAliases,
  visited = new Set(),
) {
  for (const property of objectExpression.properties) {
    if (property.type === 'SpreadElement') {
      const argument = unwrap(property.argument);
      if (
        argument?.type !== 'Identifier' ||
        visited.has(argument.name) ||
        !objectBindings.has(argument.name)
      ) {
        return false;
      }
      visited.add(argument.name);
      if (
        !inspectCrossTabObject(
          objectBindings.get(argument.name),
          objectBindings,
          sensitiveAliases,
          visited,
        )
      ) {
        return false;
      }
      continue;
    }
    if (property.type !== 'Property') {
      return false;
    }
    const key = normalizedName(staticKeyName(property));
    if (
      !SAFE_CROSS_TAB_FIELDS.has(key) ||
      expressionContainsSensitiveValue(
        property.value,
        sensitiveAliases,
        CROSS_TAB_SENSITIVE_NAMES,
      )
    ) {
      return false;
    }
  }
  return true;
}

function isPostMessageCall(node, aliases) {
  if (node.type !== 'CallExpression') {
    return false;
  }
  const callee = unwrap(node.callee);
  if (callee?.type === 'Identifier') {
    return aliases.has(callee.name);
  }
  return (
    callee?.type === 'MemberExpression' &&
    normalizedName(staticPropertyName(callee)) === 'postmessage'
  );
}

export function findCrossTabSecurityAstViolations(source, repositoryPath) {
  const ast = syntaxTree(source);
  const violations = [];
  const seen = new Set();
  const ownerPath = 'apps/web/src/auth/crossTabSync.ts';
  const normalizedPath = repositoryPath.replaceAll('\\', '/').replace(/^\.?\//, '');
  const aliases = collectBroadcastAliases(ast);
  const objectBindings = collectObjectBindings(ast);
  const sensitiveAliases = collectSensitiveAliases(ast, CROSS_TAB_SENSITIVE_NAMES);

  walk(ast, (node) => {
    if (
      node.type === 'NewExpression' &&
      isBroadcastChannelConstructor(node.callee, aliases.constructorAliases) &&
      normalizedPath !== ownerPath
    ) {
      addViolation(violations, seen, {
        line: lineOf(node),
        rule: 'single-cross-tab-session-owner',
        detail: 'BroadcastChannel session coordination is owned by auth/crossTabSync.ts',
      });
    }

    if (normalizedPath !== ownerPath || !isPostMessageCall(node, aliases.postMessageAliases)) {
      return;
    }
    const payload = unwrap(node.arguments[0]);
    if (payload?.type !== 'ObjectExpression') {
      addViolation(violations, seen, {
        line: lineOf(node),
        rule: 'inline-cross-tab-session-envelope',
        detail: 'cross-tab payloads must use a statically inspectable inline envelope',
      });
      return;
    }
    if (!inspectCrossTabObject(payload, objectBindings, sensitiveAliases)) {
      addViolation(violations, seen, {
        line: lineOf(node),
        rule: 'credential-free-cross-tab-message',
        detail: 'cross-tab session messages must not contain credentials or identity payloads',
      });
    }
  });

  return violations;
}

function importBindings(ast) {
  const refreshBindings = new Map();
  const apiNamespaces = new Set();
  const zustandFactories = new Set(['create', 'createStore']);
  const zustandNamespaces = new Set();
  walk(ast, (node) => {
    if (node.type !== 'ImportDeclaration') {
      return;
    }
    const source = node.source.value;
    for (const specifier of node.specifiers) {
      if (source === '@parkio/api-client') {
        if (specifier.type === 'ImportNamespaceSpecifier') {
          apiNamespaces.add(specifier.local.name);
        }
        if (
          specifier.type === 'ImportSpecifier' &&
          ['refreshSession', 'setRefreshHandler'].includes(
            specifier.imported.type === 'Identifier'
              ? specifier.imported.name
              : specifier.imported.value,
          )
        ) {
          refreshBindings.set(
            specifier.local.name,
            specifier.imported.type === 'Identifier'
              ? specifier.imported.name
              : specifier.imported.value,
          );
        }
      }
      if (source === 'zustand' || source === 'zustand/vanilla') {
        if (specifier.type === 'ImportNamespaceSpecifier') {
          zustandNamespaces.add(specifier.local.name);
        }
        if (
          specifier.type === 'ImportSpecifier' &&
          ['create', 'createStore'].includes(
            specifier.imported.type === 'Identifier'
              ? specifier.imported.name
              : specifier.imported.value,
          )
        ) {
          zustandFactories.add(specifier.local.name);
        }
      }
    }
  });
  return { apiNamespaces, refreshBindings, zustandFactories, zustandNamespaces };
}

function isAuthApiExpression(node, aliases) {
  const expression = unwrap(node);
  if (!expression) {
    return false;
  }
  if (expression.type === 'Identifier') {
    return aliases.has(expression.name) || normalizedName(expression.name) === 'authapi';
  }
  return memberPath(expression).map(normalizedName).includes('authapi');
}

function collectAuthApiAliases(ast) {
  const aliases = new Set(['authApi']);
  const declarators = [];
  walk(ast, (node) => {
    if (
      node.type === 'VariableDeclarator' &&
      node.id.type === 'Identifier' &&
      node.init
    ) {
      declarators.push(node);
    }
  });
  let changed = true;
  while (changed) {
    changed = false;
    for (const declarator of declarators) {
      if (
        !aliases.has(declarator.id.name) &&
        isAuthApiExpression(declarator.init, aliases)
      ) {
        aliases.add(declarator.id.name);
        changed = true;
      }
    }
  }
  return aliases;
}

function isRefreshMember(node, authApiAliases, apiNamespaces) {
  const expression = unwrap(node);
  if (expression?.type !== 'MemberExpression') {
    return false;
  }
  const propertyName = staticPropertyName(expression);
  if (propertyName === 'refresh') {
    return isAuthApiExpression(expression.object, authApiAliases);
  }
  if (!['refreshSession', 'setRefreshHandler'].includes(propertyName)) {
    return false;
  }
  const objectPath = memberPath(expression.object);
  return objectPath.some((segment) => apiNamespaces.has(segment));
}

function topLevelDeclarations(ast) {
  return ast.body.flatMap((statement) => {
    if (
      ['ExportDefaultDeclaration', 'ExportNamedDeclaration'].includes(statement.type) &&
      statement.declaration
    ) {
      return [statement.declaration];
    }
    return [statement];
  });
}

function isZustandStoreCall(node, bindings) {
  const expression = unwrap(node);
  if (expression?.type !== 'CallExpression') {
    return false;
  }
  const callee = unwrap(expression.callee);
  if (
    callee?.type === 'Identifier' &&
    (bindings.zustandFactories.has(callee.name) || callee.name === 'createAuthStore')
  ) {
    return true;
  }
  if (callee?.type !== 'MemberExpression') {
    return false;
  }
  const propertyName = staticPropertyName(callee);
  return (
    ['create', 'createStore'].includes(propertyName) &&
    memberPath(callee.object).some((segment) => bindings.zustandNamespaces.has(segment))
  );
}

function objectAuthFieldCount(node) {
  const expression = unwrap(node);
  if (expression?.type !== 'ObjectExpression') {
    return 0;
  }
  return new Set(
    expression.properties
      .filter((property) => property.type === 'Property')
      .map(staticKeyName)
      .map(normalizedName)
      .filter((key) => AUTH_IDENTITY_FIELDS.has(key)),
  ).size;
}

function subtreeAuthFields(node) {
  const fields = new Set();
  walk(node, (current) => {
    if (current.type === 'Property') {
      const key = normalizedName(staticKeyName(current));
      if (AUTH_IDENTITY_FIELDS.has(key)) {
        fields.add(key);
      }
    }
  });
  return fields;
}

function containsRefreshHttpPath(node, stringBindings) {
  let found = false;
  walk(node, (current) => {
    if (current.type === 'Literal' && typeof current.value === 'string') {
      if (/\/auth\/refresh-token(?:$|[?#/])/.test(current.value)) {
        found = true;
      }
    }
    if (
      current.type === 'Identifier' &&
      /\/auth\/refresh-token(?:$|[?#/])/.test(stringBindings.get(current.name) ?? '')
    ) {
      found = true;
    }
  });
  return found;
}

function collectStringBindings(ast) {
  const bindings = new Map();
  walk(ast, (node) => {
    if (
      node.type === 'VariableDeclarator' &&
      node.id.type === 'Identifier' &&
      unwrap(node.init)?.type === 'Literal' &&
      typeof unwrap(node.init).value === 'string'
    ) {
      bindings.set(node.id.name, unwrap(node.init).value);
    }
  });
  return bindings;
}

function networkLikeCallee(node) {
  const path = memberPath(node).map(normalizedName);
  const name = path.at(-1) ?? normalizedName(unwrap(node)?.name);
  return ['axios', 'fetch', 'get', 'post', 'put', 'request', 'send'].includes(name);
}

export function findWebAuthOwnershipAstViolations(source, repositoryPath) {
  const ast = syntaxTree(source);
  const violations = [];
  const seen = new Set();
  const normalizedPath = repositoryPath.replaceAll('\\', '/').replace(/^\.?\//, '');
  const testOwned =
    normalizedPath.includes('/test/') || /\.(?:test|spec)\.[jt]sx?$/.test(normalizedPath);
  if (testOwned) {
    return violations;
  }

  const bindings = importBindings(ast);
  const authApiAliases = collectAuthApiAliases(ast);
  const stringBindings = collectStringBindings(ast);
  const refreshOwner = 'apps/web/src/auth/session.ts';
  const handlerOwner = 'apps/web/src/app/runtime.ts';

  for (const [localName, importedName] of bindings.refreshBindings) {
    const owner = importedName === 'refreshSession' ? refreshOwner : handlerOwner;
    if (normalizedPath !== owner) {
      let importNode;
      walk(ast, (node) => {
        if (
          !importNode &&
          node.type === 'ImportSpecifier' &&
          node.local.name === localName
        ) {
          importNode = node;
        }
      });
      addViolation(violations, seen, {
        line: lineOf(importNode),
        rule: 'sdk-owned-refresh-lifecycle',
        detail: `'${importedName}' is integrated only by ${owner}`,
      });
    }
  }

  walk(ast, (node) => {
    if (node.type === 'MemberExpression' && isRefreshMember(node, authApiAliases, bindings.apiNamespaces)) {
      const propertyName = staticPropertyName(node);
      const owner = propertyName === 'setRefreshHandler' ? handlerOwner : refreshOwner;
      if (normalizedPath !== owner) {
        addViolation(violations, seen, {
          line: lineOf(node),
          rule:
            propertyName === 'refresh'
              ? 'no-web-refresh-execution'
              : 'sdk-owned-refresh-lifecycle',
          detail:
            propertyName === 'refresh'
              ? 'Web refresh integration is owned by auth/session.ts through the SDK'
              : `'${propertyName}' is integrated only by ${owner}`,
        });
      }
    }

    if (
      node.type === 'VariableDeclarator' &&
      node.id.type === 'ObjectPattern' &&
      isAuthApiExpression(node.init, authApiAliases) &&
      normalizedPath !== refreshOwner
    ) {
      for (const property of node.id.properties) {
        if (
          property.type === 'Property' &&
          normalizedName(staticKeyName(property)) === 'refresh'
        ) {
          addViolation(violations, seen, {
            line: lineOf(property),
            rule: 'no-web-refresh-execution',
            detail: 'Web refresh integration is owned by auth/session.ts through the SDK',
          });
        }
      }
    }

    if (
      node.type === 'CallExpression' &&
      networkLikeCallee(node.callee) &&
      node.arguments.some((argument) => containsRefreshHttpPath(argument, stringBindings))
    ) {
      addViolation(violations, seen, {
        line: lineOf(node),
        rule: 'no-web-refresh-execution',
        detail: 'Web must not construct an independent refresh HTTP path',
      });
    }
  });

  if (normalizedPath !== 'apps/web/src/auth/auth-store.ts') {
    for (const declaration of topLevelDeclarations(ast)) {
      if (declaration.type !== 'VariableDeclaration') {
        continue;
      }
      for (const declarator of declaration.declarations) {
        const storeFields = subtreeAuthFields(declarator.init);
        const strongAuthField = [...storeFields].some((field) =>
          ['accesstoken', 'identity', 'isauthenticated', 'sessionepoch'].includes(field),
        );
        const explicitlyAuthNamed =
          declarator.id.type === 'Identifier' &&
          /auth.*store|store.*auth/i.test(declarator.id.name);
        const explicitAuthFactory =
          unwrap(declarator.init)?.type === 'CallExpression' &&
          unwrap(unwrap(declarator.init).callee)?.type === 'Identifier' &&
          unwrap(unwrap(declarator.init).callee).name === 'createAuthStore';
        if (
          (isZustandStoreCall(declarator.init, bindings) &&
            (strongAuthField || storeFields.size >= 2 || explicitlyAuthNamed)) ||
          explicitAuthFactory ||
          (objectAuthFieldCount(declarator.init) >= 2 && strongAuthField)
        ) {
          addViolation(violations, seen, {
            line: lineOf(declarator),
            rule: 'application-scoped-auth-ownership',
            detail: 'mutable authentication state must be created by the application runtime',
          });
        }
      }
    }
  }

  return violations;
}
