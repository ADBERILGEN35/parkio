# Shared Staging Sign-off Template (WP-06.2B)

Automation must leave `signOffDecision` as `NOT_REVIEWED`.
Only an authorized human/review process may set an approval.

## Identification

| Field | Value |
|-------|-------|
| verification run ID | |
| repository commit | |
| execution environment | LOCAL_REPRESENTATIVE / CI_EPHEMERAL / SHARED_STAGING |
| infrastructure owner | |
| application owner | |
| security reviewer (if required) | |
| source environment | |
| restore environment | |
| evidence artifact reference | `build/operational-evidence/<run-id>/` |
| review date | |
| expiration / revalidation date | |

## Executed journey results

| Journey | Result |
|---------|--------|
| restored auth login | |
| refresh rotation | |
| restored parking read | |
| nearby search contract | |
| restored media | |
| post-restore write | |
| WP-05 defaults | |

## Known exclusions / baseline status

- Gateway per-route timeouts: BASELINING_REQUIRED unless approved elsewhere
- Shared staging capacity: |
- Other exclusions: |

## Remaining blockers

|

## Decision (human only)

Allowed values:

- APPROVED_FOR_WP_06_3
- APPROVED_WITH_WAIVER
- REJECTED
- EXPIRED
- NOT_REVIEWED

**Decision:** NOT_REVIEWED

**Reviewer names / team identifiers:**

|

## Waiver reference (if APPROVED_WITH_WAIVER)

See `shared-staging-waiver-template.md`. Non-waivable failures cannot be waived.

## WP-06.2B.1 usage

Copy this template into the evidence run directory (for example uild/operational-evidence/<run-id>/shared-staging-signoff.md), prepopulate factual fields only, and leave **Decision: NOT_REVIEWED** until a human reviewer acts. See docs/operations/wp-06-02b-1-evidence-finalization-signoff-preparation.md.

## WP-06.2B.2 usage

For final-state certification, copy into `build/operational-evidence/<new-run-id>/human-signoff.md` after a fresh restored-stack run on the current worktree. Do not reuse a historical run ID. Keep **Decision: NOT_REVIEWED** until a human acts. See docs/operations/wp-06-02b-2-final-state-reexecution-signoff-gate.md.