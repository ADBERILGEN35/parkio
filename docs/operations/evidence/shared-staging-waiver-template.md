# Shared Staging Waiver Template (WP-06.2B)

Use only for a named **external** limitation after all non-waivable checks pass.

## Waiver fields

| Field | Value |
|-------|-------|
| exact blocked verification | |
| reason | |
| owner | |
| risk | |
| compensating control | |
| expiration date | |
| revalidation trigger | |
| approving role | |

## Non-waivable (must remain FAIL / BLOCKED)

- production-target safety failure
- source/restore database equality
- missing isolation
- production data or credentials
- authority expansion
- failed restored login
- failed restored parking read
- corrupted restore
- checksum mismatch
- secrets exposure
