# 09 — Prompt Rules (for AI coding agents)

Read this **before** writing any code in Parkio.

## Before coding

1. **Read `/docs/ai-context` first** — at minimum `00`, `01`, the rule files
   relevant to your task (`03` boundaries, plus the API/DB/event/security file that
   applies), and this file.
2. Do **not** assume services are empty scaffolding. Parkio has real
   implementation, tests, schemas, security/session/media/outbox behavior and
   frontend flows. Read the target service README plus relevant
   `docs/architecture/*` files before modifying code.
3. Identify the **single service** you are changing. Confirm the change belongs to
   that service's bounded context (`03-service-boundaries.md`).
4. If the task is ambiguous or seems to require crossing a boundary, **ask** rather
   than guess.

## While coding

- **Keep changes small and task-focused.** One task = one coherent change. Don't
  refactor unrelated code or touch unrelated services.
- **Do not implement unrelated services.** Stay in scope.
- **Never invent cross-service shared domain models** or a common domain library.
  Reference other services by ID; integrate via REST/Feign or Kafka events.
- **Do not access another service's database.**
- Respect clean architecture: keep `domain` free of framework code (`01`, `08`).
- Prefer **production-friendly but not overengineered** code. No speculative
  abstractions, no patterns the task doesn't need. Match existing style.
- Use existing build wiring (convention plugin + version catalog); add new
  dependencies there, not ad hoc.
- Preserve established security/session/media/outbox patterns unless the task
  explicitly asks to change them. Never overwrite real implementation with
  placeholder scaffolding.

## Reliability & correctness

- For write/command and event-consuming code, apply **idempotency** and the
  **outbox/inbox** patterns (`06`).
- Follow API, DB, event, and security guidelines (`04`–`07`).

## After coding

- **Add tests for the behavior you implemented** (`08`). No business logic without
  tests.
- Ensure `./gradlew build` passes (including the `contextLoads` smoke tests).
- **Keep documentation concise.** If behavior changes a rule here, update the
  relevant `ai-context` file briefly — don't bloat it.

## Hard limits (do not cross without explicit instruction)

- No modular monolith; services stay independent.
- No shared domain module; no cross-service DB access.
- AI validation is **advisory only** — it never makes final accept/reject/ban
  decisions (`02`).
- Don't implement business logic that wasn't requested, and don't modify scaffolding
  conventions unless the task is explicitly about them.
