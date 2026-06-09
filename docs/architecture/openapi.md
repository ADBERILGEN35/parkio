# OpenAPI — service-level API contracts

Every REST backend service (except the reactive gateway) exposes machine-readable
OpenAPI 3 documentation via [springdoc-openapi](https://springdoc.org/) when
`PARKIO_OPENAPI_ENABLED=true` (the default locally; set `false` in production).

| Service | Direct URL (local) | Swagger UI | Gateway path prefix |
|---------|-------------------|------------|---------------------|
| auth-service | http://localhost:8081/v3/api-docs | http://localhost:8081/swagger-ui.html | `/api/v1/auth/**` |
| user-service | http://localhost:8082/v3/api-docs | http://localhost:8082/swagger-ui.html | `/api/v1/users/**` |
| parking-service | http://localhost:8083/v3/api-docs | http://localhost:8083/swagger-ui.html | `/api/v1/parking/**` |
| media-service | http://localhost:8084/v3/api-docs | http://localhost:8084/swagger-ui.html | `/api/v1/media/**` |
| gamification-service | http://localhost:8085/v3/api-docs | http://localhost:8085/swagger-ui.html | `/api/v1/gamification/**` |
| notification-service | http://localhost:8086/v3/api-docs | http://localhost:8086/swagger-ui.html | `/api/v1/notifications/**` |
| moderation-service | http://localhost:8087/v3/api-docs | http://localhost:8087/swagger-ui.html | `/api/v1/moderation/**` |
| ai-validation-service | http://localhost:8088/v3/api-docs | http://localhost:8088/swagger-ui.html | `/api/v1/ai-validations/**` |
| analytics-service | http://localhost:8089/v3/api-docs | http://localhost:8089/swagger-ui.html | `/api/v1/analytics/**` |

The **gateway** (port 8080) is the only public entrypoint for clients. It does
not aggregate OpenAPI specs — each service documents its own contract. Frontend
and mobile teams should use the **gateway path prefixes** above when calling APIs
in dev/staging/prod; the direct service ports are for local debugging and doc
browsing only.

## Security schemes

Each spec defines:

- **`bearerAuth`** — RS256 JWT from `POST /api/v1/auth/login`. Send as
  `Authorization: Bearer <accessToken>` through the gateway.
- **`gatewayAuth`** — `X-Gateway-Auth` header (internal only; documented for
  completeness, not for browser clients).

Internal endpoints (`/internal/**`) are annotated `@Hidden` and do not appear
in the public OpenAPI document. Actuator endpoints are excluded
(`springdoc.show-actuator=false`).

## Error shape

All documented error responses use the service-local `ApiError` schema:

```json
{
  "code": "SPOT_NOT_FOUND",
  "message": "Human-readable summary",
  "traceId": "correlation-id",
  "timestamp": "2026-06-09T10:00:00Z",
  "fieldErrors": [{ "field": "email", "message": "must not be blank" }]
}
```

`fieldErrors` is omitted unless the failure is a validation error. The gateway
may return its own edge `ApiError` for `401`/`403`/`429` before a request
reaches a downstream service.

## Configuration

```yaml
# application.yml (every REST service)
parkio:
  openapi:
    enabled: ${PARKIO_OPENAPI_ENABLED:true}   # false in production

springdoc:
  api-docs:
    enabled: ${parkio.openapi.enabled:true}
  swagger-ui:
    enabled: ${parkio.openapi.enabled:true}
  show-actuator: false
```

OpenAPI and Swagger UI paths skip `X-Gateway-Auth` (like actuator) so docs are
reachable on a direct service port during local development without the gateway
secret.

## Frontend usage

1. Obtain an access token via the gateway: `POST http://localhost:8080/api/v1/auth/login`.
2. Call resource APIs through the gateway with `Authorization: Bearer <token>`.
3. Use each service's `/v3/api-docs` JSON (or Swagger UI) to generate TypeScript
   clients — generate against gateway path prefixes, not internal service ports.
4. Treat `429` as gateway rate-limit rejection (edge only).

Gateway route → service mapping is defined in
`services/gateway-service/src/main/resources/application.yml` under
`spring.cloud.gateway.routes`.
