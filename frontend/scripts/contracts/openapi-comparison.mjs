import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

function asObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? value : undefined;
}

function schemaReferenceName(schema) {
  const object = asObject(schema);
  if (!object) return undefined;

  if (typeof object.$ref === 'string') {
    return object.$ref.split('/').at(-1);
  }

  for (const composition of ['allOf', 'anyOf', 'oneOf']) {
    if (!Array.isArray(object[composition])) continue;
    for (const member of object[composition]) {
      const name = schemaReferenceName(member);
      if (name) return name;
    }
  }

  return undefined;
}

function responseSchema(operation, status) {
  return operation?.responses?.[status]?.content?.['application/json']?.schema;
}

function requestSchema(operation) {
  return operation?.requestBody?.content?.['application/json']?.schema;
}

function sortedStrings(values) {
  return [...values].sort((left, right) => left.localeCompare(right));
}

function sameStrings(actual, expected) {
  return JSON.stringify(sortedStrings(actual)) === JSON.stringify(sortedStrings(expected));
}

function composedSchemas(schema) {
  const object = asObject(schema);
  if (!object) return [];
  return ['allOf', 'anyOf', 'oneOf'].flatMap((key) =>
    Array.isArray(object[key]) ? object[key] : [],
  );
}

function schemaType(schema) {
  const object = asObject(schema);
  if (!object) return undefined;
  if (typeof object.type === 'string') return object.type === 'null' ? undefined : object.type;
  if (Array.isArray(object.type)) {
    const nonNullTypes = object.type.filter((value) => value !== 'null');
    return nonNullTypes.length === 1 ? nonNullTypes[0] : undefined;
  }

  const composedTypes = composedSchemas(object)
    .map((member) => schemaType(member))
    .filter((value) => value !== undefined);
  return [...new Set(composedTypes)].length === 1 ? composedTypes[0] : undefined;
}

function schemaKeyword(schema, keyword) {
  const object = asObject(schema);
  if (!object) return undefined;
  if (Object.hasOwn(object, keyword)) return object[keyword];
  for (const member of composedSchemas(object)) {
    const value = schemaKeyword(member, keyword);
    if (value !== undefined) return value;
  }
  return undefined;
}

function schemaIsNullable(schema) {
  const object = asObject(schema);
  if (!object) return false;
  if (object.nullable === true) return true;
  if (Array.isArray(object.type) && object.type.includes('null')) return true;
  return composedSchemas(object).some((member) => {
    const candidate = asObject(member);
    return candidate?.type === 'null' || schemaIsNullable(candidate);
  });
}

function compareRepresentation(actual, expected, label, errors) {
  if (!asObject(actual)) {
    errors.push(`${label}: property schema is missing`);
    return;
  }

  if (expected.ref !== undefined && schemaReferenceName(actual) !== expected.ref) {
    errors.push(`${label}: expected reference ${expected.ref}`);
  }

  if (expected.type !== undefined && schemaType(actual) !== expected.type) {
    errors.push(`${label}: expected type ${expected.type}, received ${String(schemaType(actual))}`);
  }

  if (expected.nullable !== undefined && schemaIsNullable(actual) !== expected.nullable) {
    errors.push(`${label}: expected nullable=${String(expected.nullable)}`);
  }

  for (const keyword of [
    'format',
    'pattern',
    'minimum',
    'maximum',
    'minLength',
    'maxLength',
  ]) {
    if (!Object.hasOwn(expected, keyword)) continue;
    const actualValue = schemaKeyword(actual, keyword);
    if (actualValue !== expected[keyword]) {
      errors.push(
        `${label}: expected ${keyword}=${JSON.stringify(expected[keyword])}, received ${JSON.stringify(actualValue)}`,
      );
    }
  }

  if (expected.items !== undefined) {
    const actualItems = schemaKeyword(actual, 'items');
    compareRepresentation(actualItems, expected.items, `${label} items`, errors);
  }
}

export function compareOpenApiDocument(document, manifest) {
  const errors = [];
  const paths = asObject(document?.paths) ?? {};
  const schemas = asObject(document?.components?.schemas) ?? {};

  for (const expected of manifest.operations ?? []) {
    const operation = paths[expected.path]?.[expected.method];
    const label = `${expected.method.toUpperCase()} ${expected.path}`;
    if (!operation) {
      errors.push(`${label}: operation is missing`);
      continue;
    }

    if (operation.operationId !== expected.operationId) {
      errors.push(
        `${label}: expected operationId ${expected.operationId}, received ${String(operation.operationId)}`,
      );
    }

    const actualRequestSchema = requestSchema(operation);
    if (expected.requestSchema === null) {
      if (operation.requestBody !== undefined) {
        errors.push(`${label}: requestBody must be absent`);
      }
    } else if (schemaReferenceName(actualRequestSchema) !== expected.requestSchema) {
      errors.push(`${label}: expected request schema ${expected.requestSchema}`);
    }

    for (const [status, expectedSchema] of Object.entries(expected.responses ?? {})) {
      if (!operation.responses?.[status]) {
        errors.push(`${label}: response ${status} is missing`);
        continue;
      }

      const actualResponseSchema = responseSchema(operation, status);
      if (expectedSchema === null) {
        if (actualResponseSchema !== undefined) {
          errors.push(`${label}: response ${status} must not declare a JSON body schema`);
        }
      } else if (schemaReferenceName(actualResponseSchema) !== expectedSchema) {
        errors.push(`${label}: response ${status} expected schema ${expectedSchema}`);
      }
    }
  }

  for (const [schemaName, expected] of Object.entries(manifest.schemas ?? {})) {
    const actual = schemas[schemaName];
    if (!actual) {
      errors.push(`schema ${schemaName}: component is missing`);
      continue;
    }

    const actualProperties = Object.keys(actual.properties ?? {});
    const expectedProperties = expected.properties ?? [];
    if (expected.mode === 'request') {
      if (!sameStrings(actualProperties, expectedProperties)) {
        errors.push(`schema ${schemaName}: request properties differ from the frozen contract`);
      }
    } else {
      for (const property of expectedProperties) {
        if (!actualProperties.includes(property)) {
          errors.push(`schema ${schemaName}: response property ${property} is missing`);
        }
      }
    }

    const expectedRequired = expected.required ?? [];
    const actualRequired = actual.required ?? [];
    for (const requiredProperty of expectedRequired) {
      if (!(actual.required ?? []).includes(requiredProperty)) {
        errors.push(`schema ${schemaName}: required property ${requiredProperty} is missing`);
      }
    }
    for (const requiredProperty of actualRequired) {
      if (!expectedRequired.includes(requiredProperty)) {
        errors.push(`schema ${schemaName}: required property ${requiredProperty} was added`);
      }
    }

    if (
      Object.hasOwn(expected, 'additionalProperties') &&
      actual.additionalProperties !== expected.additionalProperties
    ) {
      errors.push(
        `schema ${schemaName}: expected additionalProperties=${String(expected.additionalProperties)}`,
      );
    }

    for (const [property, expectedValues] of Object.entries(expected.enums ?? {})) {
      const actualValues = actual.properties?.[property]?.enum;
      if (!Array.isArray(actualValues) || !sameStrings(actualValues, expectedValues)) {
        errors.push(`schema ${schemaName}: enum ${property} differs from the frozen contract`);
      }
    }

    for (const [property, representation] of Object.entries(expected.representations ?? {})) {
      compareRepresentation(
        actual.properties?.[property],
        representation,
        `schema ${schemaName}: property ${property}`,
        errors,
      );
    }
  }

  return errors;
}

export async function loadJson(path) {
  return JSON.parse(await readFile(path, 'utf8'));
}

async function runCli() {
  const [openApiPath, manifestPath = 'contracts/sprint-2.3/parking-openapi-manifest.json'] =
    process.argv.slice(2);
  if (!openApiPath) {
    console.error('Usage: pnpm contracts:openapi <openapi-json> [manifest-json]');
    process.exitCode = 2;
    return;
  }

  const [document, manifest] = await Promise.all([loadJson(openApiPath), loadJson(manifestPath)]);
  const errors = compareOpenApiDocument(document, manifest);
  if (errors.length > 0) {
    for (const error of errors) console.error(error);
    process.exitCode = 1;
    return;
  }

  console.log(`OpenAPI contract comparison passed: ${manifest.service}`);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  await runCli();
}
