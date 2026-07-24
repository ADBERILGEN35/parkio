import assert from 'node:assert/strict';
import { test } from 'node:test';
import { URL } from 'node:url';
import { compareOpenApiDocument, loadJson } from './openapi-comparison.mjs';

const manifest = {
  operations: [
    {
      method: 'post',
      path: '/sessions',
      operationId: 'startSession',
      requestSchema: 'StartRequest',
      responses: { 201: 'SessionResponse' },
    },
    {
      method: 'post',
      path: '/spots/{spotId}/claim',
      operationId: 'claimSpot',
      requestSchema: null,
      responses: { 200: 'SpotResponse' },
    },
  ],
  schemas: {
    StartRequest: {
      mode: 'request',
      properties: ['latitude', 'note'],
      required: ['latitude'],
      additionalProperties: false,
      representations: {
        latitude: { type: 'number', format: 'double', minimum: -90, maximum: 90 },
        note: { type: 'string', nullable: true, pattern: '^[A-Z]+$', maxLength: 32 },
      },
    },
    SessionResponse: {
      mode: 'response',
      properties: ['id', 'status', 'endedAt', 'tags'],
      required: ['id', 'status', 'endedAt', 'tags'],
      enums: { status: ['ACTIVE', 'COMPLETED'] },
      representations: {
        id: { type: 'string', format: 'uuid' },
        status: { type: 'string' },
        endedAt: { type: 'string', format: 'date-time', nullable: true },
        tags: { type: 'array', items: { type: 'string' } },
      },
    },
    SpotResponse: {
      mode: 'response',
      properties: ['id'],
    },
  },
};

function ref(name) {
  return { $ref: `#/components/schemas/${name}` };
}

function schemaFromRepresentation(representation = { type: 'string' }) {
  const { ref: reference, items, ...keywords } = representation;
  return {
    ...(reference === undefined ? keywords : ref(reference)),
    ...(items === undefined ? {} : { items: schemaFromRepresentation(items) }),
  };
}

function validDocument() {
  return {
    paths: {
      '/sessions': {
        post: {
          operationId: 'startSession',
          requestBody: { content: { 'application/json': { schema: ref('StartRequest') } } },
          responses: {
            201: { content: { 'application/json': { schema: ref('SessionResponse') } } },
          },
        },
      },
      '/spots/{spotId}/claim': {
        post: {
          operationId: 'claimSpot',
          responses: {
            200: { content: { 'application/json': { schema: ref('SpotResponse') } } },
          },
        },
      },
    },
    components: {
      schemas: {
        StartRequest: {
          type: 'object',
          additionalProperties: false,
          required: ['latitude'],
          properties: {
            latitude: { type: 'number', format: 'double', minimum: -90, maximum: 90 },
            note: { type: ['string', 'null'], pattern: '^[A-Z]+$', maxLength: 32 },
          },
        },
        SessionResponse: {
          type: 'object',
          required: ['id', 'status', 'endedAt', 'tags'],
          properties: {
            id: { type: 'string', format: 'uuid' },
            status: { type: 'string', enum: ['ACTIVE', 'COMPLETED'] },
            endedAt: { type: ['string', 'null'], format: 'date-time' },
            tags: { type: 'array', items: { type: 'string' } },
            additiveField: { type: 'string' },
          },
        },
        SpotResponse: {
          type: 'object',
          properties: { id: { type: 'string' } },
        },
      },
    },
  };
}

test('accepts additive response fields while keeping request schemas exact', () => {
  assert.deepEqual(compareOpenApiDocument(validDocument(), manifest), []);
});

test('detects request-body drift on a bodyless operation', () => {
  const document = validDocument();
  document.paths['/spots/{spotId}/claim'].post.requestBody = {
    content: { 'application/json': { schema: ref('StartRequest') } },
  };

  assert.ok(
    compareOpenApiDocument(document, manifest).includes(
      'POST /spots/{spotId}/claim: requestBody must be absent',
    ),
  );
});

test('detects missing fields, enum drift, and open request objects', () => {
  const document = validDocument();
  document.components.schemas.StartRequest.additionalProperties = true;
  document.components.schemas.SessionResponse.required = ['id'];
  document.components.schemas.SessionResponse.properties.status.enum = ['ACTIVE', 'PAUSED'];

  const errors = compareOpenApiDocument(document, manifest);
  assert.ok(errors.some((error) => error.includes('additionalProperties=false')));
  assert.ok(errors.some((error) => error.includes('required property status')));
  assert.ok(errors.some((error) => error.includes('enum status differs')));
});

test('detects added required fields and optional-to-required transitions', () => {
  const document = validDocument();
  document.components.schemas.SessionResponse.required.push('additiveField');
  document.components.schemas.StartRequest.required.push('note');

  const errors = compareOpenApiDocument(document, manifest);
  assert.ok(
    errors.includes('schema SessionResponse: required property additiveField was added'),
  );
  assert.ok(errors.includes('schema StartRequest: required property note was added'));
});

test('detects primitive, format, nullability, and collection-shape drift', () => {
  const document = validDocument();
  document.components.schemas.StartRequest.properties.latitude.format = 'float';
  document.components.schemas.StartRequest.properties.latitude.minimum = -180;
  document.components.schemas.StartRequest.properties.note.pattern = '.*';
  document.components.schemas.StartRequest.properties.note.maxLength = 64;
  document.components.schemas.SessionResponse.properties.id.type = 'number';
  document.components.schemas.SessionResponse.properties.endedAt.type = 'string';
  document.components.schemas.SessionResponse.properties.tags.items.type = 'number';

  const errors = compareOpenApiDocument(document, manifest);
  assert.ok(errors.some((error) => error.includes('property latitude: expected format="double"')));
  assert.ok(errors.some((error) => error.includes('property latitude: expected minimum=-90')));
  assert.ok(errors.some((error) => error.includes('property note: expected pattern="^[A-Z]+$"')));
  assert.ok(errors.some((error) => error.includes('property note: expected maxLength=32')));
  assert.ok(errors.some((error) => error.includes('property id: expected type string')));
  assert.ok(errors.some((error) => error.includes('property endedAt: expected nullable=true')));
  assert.ok(errors.some((error) => error.includes('property tags items: expected type string')));
});

test('the checked-in Sprint 2.3 manifest is internally comparable', async () => {
  const frozenManifest = await loadJson(
    new URL('../../contracts/sprint-2.3/parking-openapi-manifest.json', import.meta.url),
  );
  const document = { paths: {}, components: { schemas: {} } };

  for (const [name, expected] of Object.entries(frozenManifest.schemas)) {
    const properties = Object.fromEntries(
      expected.properties.map((property) => [
        property,
        {
          ...schemaFromRepresentation(expected.representations?.[property]),
          ...(expected.enums?.[property] ? { enum: expected.enums[property] } : {}),
        },
      ]),
    );
    document.components.schemas[name] = {
      type: 'object',
      properties,
      required: expected.required ?? [],
      ...(Object.hasOwn(expected, 'additionalProperties')
        ? { additionalProperties: expected.additionalProperties }
        : {}),
    };
  }

  for (const expected of frozenManifest.operations) {
    const responses = Object.fromEntries(
      Object.entries(expected.responses).map(([status, schema]) => [
        status,
        schema === null
          ? { description: 'No content' }
          : { content: { 'application/json': { schema: ref(schema) } } },
      ]),
    );
    const operation = {
      operationId: expected.operationId,
      responses,
      ...(expected.requestSchema === null
        ? {}
        : {
            requestBody: {
              content: { 'application/json': { schema: ref(expected.requestSchema) } },
            },
          }),
    };
    document.paths[expected.path] ??= {};
    document.paths[expected.path][expected.method] = operation;
  }

  assert.deepEqual(compareOpenApiDocument(document, frozenManifest), []);
});
