# 04 — API Guidelines

Applies to synchronous REST APIs exposed by services (behind `gateway-service`).

## Style

- **REST/JSON.** Resource-oriented, plural nouns: `/api/v1/spots`,
  `/api/v1/users/{id}`.
- **Versioned** path prefix: `/api/v1/...`. Breaking changes → new version.
- Use proper HTTP methods/status codes:
  - `GET` (safe), `POST` (create), `PUT`/`PATCH` (update), `DELETE` (remove).
  - `200/201/204` success, `400` validation, `401/403` auth, `404` missing,
    `409` conflict, `422` business-rule rejection, `429` rate limit, `5xx` server.
- Pagination for collections: `?page=&size=` (or cursor for large/geo sets);
  return `page`, `size`, `totalElements` style metadata.
- Geo queries (parking search) use explicit params: `lat`, `lng`, `radiusMeters`,
  optional filters (`vehicleType`, `parkingContext`, `legalStatus`).

## Contracts

- DTOs live in `presentation`; **never expose domain entities directly**.
- Validate inputs with `spring-boot-starter-validation` (`@Valid`, constraints) in
  `presentation`; map to domain commands in `application`.
- Document every public endpoint with **OpenAPI**; keep specs under
  `docs/services/<service>/`.

## Errors

- Consistent error body, e.g.:
  ```json
  { "code": "SPOT_EXPIRED", "message": "...", "traceId": "..." }
  ```
- Map domain exceptions to HTTP via `@RestControllerAdvice` in `presentation`.
  Never leak stack traces or internal details.

## Idempotency & concurrency

- **Write endpoints** accept an `Idempotency-Key` header; replays return the
  original result (see `06`). Required for create/claim operations.
- Use optimistic locking (version field) for spot status transitions; conflicts →
  `409`.

## Inter-service calls

- Use **OpenFeign** clients, defined in `infrastructure`.
- Set timeouts and a fallback/circuit breaker (Spring Cloud / Resilience4j).
- Propagate auth (token) and `traceId` headers. Pass IDs, not embedded objects.
- Do not chain synchronous calls deeply; prefer events for side effects.

## Auth

- `gateway-service` enforces authentication and forwards identity downstream.
- Services authorize per-endpoint using roles/claims from `auth-service` tokens.
