# ai-context

Concise project rules for **AI coding agents** working on Parkio. Read these
**before** writing any code. Start with `09-prompt-rules.md`.

| File                          | What it covers                                         |
|-------------------------------|--------------------------------------------------------|
| `00-project-overview.md`      | What Parkio is, services, tech stack, current state.   |
| `01-architecture-rules.md`    | Microservice + clean-architecture rules (mandatory).   |
| `02-domain-rules.md`          | Statuses, validity, vehicles, context, legal, scoring. |
| `03-service-boundaries.md`    | Who owns what; allowed cross-service interactions.     |
| `04-api-guidelines.md`        | REST/OpenFeign conventions.                            |
| `05-database-guidelines.md`   | DB-per-service, PostGIS, migrations, Redis.            |
| `06-event-guidelines.md`      | Kafka events, outbox/inbox, idempotency.               |
| `07-security-guidelines.md`   | Auth, secrets, media, abuse, privacy.                  |
| `08-coding-standards.md`      | Java 21 / Spring style, testing, what not to do.       |
| `09-prompt-rules.md`          | How agents should approach a task (read first).        |

These are documentation only. They describe the rules for implementing business
logic that does **not** exist yet.
