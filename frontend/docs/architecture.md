# Frontend architecture notes

See the root [`../README.md`](../README.md) for install, env vars, and operational guidance.

## Package boundaries

| Package | Responsibility |
|---------|----------------|
| `@parkio/types` | DTO shapes mirroring backend responses — no runtime logic |
| `@parkio/validation` | Zod schemas for forms and `ApiError` parsing |
| `@parkio/api-client` | Axios instance, auth/parking/media clients, error types, idempotency |
| `@parkio/ui` | Stateless presentational components + design tokens |
| `@parkio/web` | Routing, auth state, pages, app wiring |

Business rules stay on the backend. The frontend validates input shape and renders API results.

## API access

Single entrypoint: gateway `VITE_API_BASE_URL`. OpenAPI specs live per service (`/v3/api-docs`) for contract reference — generate clients against gateway path prefixes, not internal ports.
