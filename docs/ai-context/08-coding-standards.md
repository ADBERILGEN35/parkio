# 08 — Coding Standards

## Language & frameworks

- **Java 21**, Spring Boot 3, Spring Cloud. Use modern Java: records for
  DTOs/value objects, sealed types where they clarify intent, pattern matching,
  `var` for obvious locals, `Optional` for absent values (not for fields/params).
- Build with the **Gradle wrapper** (`./gradlew`). Add shared versions to the
  version catalog (`gradle/libs.versions.toml`); services apply the
  `parkio.spring-service` convention plugin.

## Package & layer discipline

- Respect clean architecture (see `01`): `domain` is framework-free. No Spring/JPA/
  Kafka/HTTP annotations in `domain`.
- One aggregate's logic stays cohesive; controllers thin, use cases in
  `application`, persistence/integration in `infrastructure`.
- Constructor injection only (no field `@Autowired`). Prefer immutability;
  package-private/`final` by default.

## Style

- Follow standard Java conventions: `PascalCase` types, `camelCase` members,
  `UPPER_SNAKE_CASE` constants. Meaningful names; no abbreviations that obscure.
- Small methods, early returns, no deep nesting. No dead code or commented-out
  blocks. Keep comments for *why*, not *what*.
- Match the style of surrounding code in a service.

## Errors & nullability

- Throw specific domain exceptions; translate to HTTP in `presentation`
  (`@RestControllerAdvice`). Don't swallow exceptions.
- Avoid returning `null` from public methods; use `Optional` or throw.
- Validate at boundaries (`presentation`), keep `domain` invariants enforced in
  constructors/factory methods.

## Concurrency & reliability

- Make writes idempotent; consumers must tolerate redelivery (see `06`).
- Use optimistic locking for contended state transitions.
- No blocking calls on reactive paths (`gateway-service` is reactive/WebFlux).

## Testing

- **Add tests for every behavior you implement.** No business logic without tests.
- Unit-test `domain`/`application` without Spring (fast, pure).
- Use `@SpringBootTest` / slice tests for adapters; Testcontainers for
  Postgres/Kafka/Redis integration where it adds value.
- Keep the existing `contextLoads` smoke test green. `./gradlew build` must pass.

## Logging & observability

- Use SLF4J; structured logs with `traceId`. No secrets/PII in logs (see `07`).
- Expose actuator health/info (already configured); add meaningful metrics for new
  hot paths.

## Don't

- Don't add a shared domain library or cross-service models.
- Don't access another service's DB.
- Don't over-engineer: no speculative abstractions, frameworks, or patterns a task
  doesn't need (see `09`).
