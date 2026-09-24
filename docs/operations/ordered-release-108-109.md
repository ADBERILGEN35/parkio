# Ordered release package — #108 then #109

Draft coordination only. Not authorization to merge, undraft, install, deploy,
decrypt, restore, or change production.

This is unsafe-restore prevention plus a scoped web-map deploy guard.
It is not completed production recovery and not MapTiler authorization.

## 0. Exact current state (fetched 2026-09-24)

| Object | SHA | Draft | Notes |
|---|---|---|---|
| `origin/api` | `b5a86ed8d432d0840e94796ee63781ddf2c4420c` | n/a | Includes deployed #106/#107 and installed #105 |
| #108 `fix/web-synthetic-map-deploy-guard` | `1e14f3f3e8aa8d3f2cb4abc47b45358660c7076f` | yes | Matches reviewed candidate. No delta. |
| #109 `fix/restore-safe-preflight` | `f3bfe297c4db8985757ed4b4a675e869470ed7a4` | yes | **Tested code head.** Matches reviewed candidate. No delta. |
| #104 HOLD | `c24f4f3de07d31c359c33971aba457041714705c` | yes | Untouched. No merge, rebase, or enablement. |
| #101 / #102 / #103 | — | — | Untouched. |

This documentation commit, if present on #109 after `f3bfe297`, is a
**docs head** only. Do not carry the `f3bfe297` CI evidence onto a later
implementation SHA.

Previously accepted production work (unchanged by this package):

- #106 gateway ACL and #107 gateway pin are deployed.
- #105 backup COMPLETE-gate scripts are installed.
- First eligible #105 scheduled backup after installation: **2026-09-25T03:30:00Z**.
  Do not observe it from this task.

### Branch protection on `api`

- Required status checks (**strict**, must be present on the exact merge head):
  `Build & unit tests`, `Secret scan`.
- `enforce_admins`: true. No bypass.
- Required reviews: 0; stale reviews dismissed.
- No push restrictions beyond that.

### #108 changed files

`.github/workflows/invite-production-deploy.yml`,
`agent-tools/parkio-f05-web-map-deploy-guard-01/RELEASE-PACKAGE.md`,
`scripts/guard-web-synthetic-map-deploy.sh`,
`scripts/lib/deploy-common.sh`,
`scripts/lib/web-map-guard.sh` (new),
`scripts/lib/web_bundle_map_config.py` (new),
`scripts/parkio-prod-compose.sh`,
`scripts/test-guard-web-synthetic-map-deploy.sh`.

Required checks on this head: **pass**. Affected: Invite-production
`Build images + secret-safe dry-run manifest` **pass** (includes
`PARKIO_GUARD_TEST_REQUIRE_DOCKER=1 bash scripts/test-guard-web-synthetic-map-deploy.sh`
→ **139/139 assertions**). Security CI **pass**. Deploy/rollback/migrate/runner
jobs **skipped** (non-deploy). 19 pass + 4 skip.

### #109 changed files

`.github/workflows/observability-validation.yml`,
`.github/workflows/restore-drill-01-procedure.yml`,
`HOSTED-BETA-RUNBOOK.md`,
`docs/operations/backup-restore-readiness.md`,
`docs/operations/backup-restore.md`,
`docs/operations/disaster-recovery-runbook.md`,
`docs/operations/restore-drill-01-isolated-database.md`,
`docs/operations/restore-runbook.md`,
`docs/operations/restore-safe-preflight.md`,
`scripts/lib/restore-erasure-ledger.py`,
`scripts/lib/restore-safe-preflight.sh` (new),
`scripts/lib/restore-stamp-preflight.py`,
`scripts/restore-database.sh`,
`scripts/restore-hosted-beta.sh`,
`scripts/test-restore-safe-preflight.sh` (new),
`scripts/test_restore_readiness.py`.

Required checks on this head: **pass**. Affected: Observability
`Config + script checks` **pass** (`23 passed, 0 failed` in
`scripts/test-restore-safe-preflight.sh`); Restore drill 01
`Encrypted stamp → isolated restore → parity + erasure` **pass**;
Backup restore drill **pass**; Backend CI **pass**; Security CI **pass**.
26 checks, 0 failures (22 pass + 4 skip). Restore drill 01 does **not**
call `restore-hosted-beta.sh` or `restore-database.sh`.

---

## 1. Focused review — #108 web map deploy guard

**Decision: PASS** (repository merge of this SHA). Host activation is Stage B.

### Supported web-creating callers

Every supported caller that can create or recreate `web` uses the binding
override as the last `-f`:

| Caller | Path |
|---|---|
| Civo wrapper | `scripts/parkio-prod-compose.sh` renders the merged model (canonical files + operator `-f`/`--profile`), binds, then runs the subcommand with the override last. |
| Deploy / rollback | `parkio_compose_up` in `scripts/lib/deploy-common.sh` → `parkio_web_guard_before_up` → last `-f`. Used by `deploy-hosted-beta.sh`, `deploy-invite-production.sh`, `rollback-hosted-beta.sh`. `rollback-invite-production.sh` is a 9-line `exec` of `rollback-hosted-beta.sh`. |

`PARKIO_SKIP_WEB_MAP_GUARD=1` is refused. Break-glass is only
`I_ACCEPT_UNVERIFIED_WEB_IMAGE` and disables both verification and binding.

### Image identity bound to the executed Compose command

`parkio_web_guard_bind` verifies `services.web` of the rendered model, then
writes `{"services":{"web":{"image": <BOUND>, "pull_policy": "never"}}}`
(mode 0600) only on PASS. `<BOUND>` is `repo@sha256:<manifest>` for a digest
pin, otherwise the verified config ID.

Covered substitutions: mutable tags, operator overlays, `pull_policy`
always/newer/build, implicit build, platform mismatch,
`DOCKER_DEFAULT_PLATFORM`, unrecognized argument parsing (ambiguous ⇒ guard,
never skip). `--build` and `--pull always|newer|build` are **refused**
before Compose.

Override lifetime: 0700 temp dir, deleted on EXIT / after `parkio_compose_up`.
Guard failure returns 1 and starts nothing (`set -e` callers stop). Compose
failure returns the Compose exit code after cleanup.

This package does **not** claim MapTiler authorization or an approved key
fingerprint. `fingerprintCheck` on the accepted pin is `not-requested`.

### Four-file host-install closure (Stage B only)

`deploy-common.sh` is **excluded** from the Civo host copy. Deploy and rollback
scripts must run from a **clean checkout of the merged release SHA**, not from
the drifted host tree.

Blob SHA-256 at `1e14f3f3` (git object bytes):

| File | Action | Pre-image (`b5a86ed8`) | Post-image (`1e14f3f3`) |
|---|---|---|---|
| `scripts/parkio-prod-compose.sh` | replace | `f8cfa8bec4d979288bfcd80b65c9ff78acb6e683d264903c16e1cac647ff2ea0` | `08492bc5ea9a8a2047ac9835212298998c042166b6ead6ae94262e7942b7e97a` |
| `scripts/guard-web-synthetic-map-deploy.sh` | replace | `940ec1fefdc3f9f1164d1a60f04cb4ad2232a9d7616022c280915849d9c637f5` | `49a21daf3d870ec81f572da62fb79e1cfc8b8eb3a8998f8a1773c398992fc5f0` |
| `scripts/lib/web-map-guard.sh` | new (must be absent) | — | `ed2d13474aa473a3341c704837bd9c90f71e9661cdb877d1246caa42f5149a68` |
| `scripts/lib/web_bundle_map_config.py` | new (must be absent) | — | `c02c4d4f13a2c20588f6f34cf7cdb85040b15dbd8acd433bdcba7e8acc17bf68` |

Excluded: `scripts/lib/deploy-common.sh` post-image
`6c300a9774084fcb8316996142a772945b9c28c51dda2d7bd359dd48f94fa8a3`.

After the four-file install, only the **Civo wrapper** path is protected.
`parkio_compose_up` callers are protected only when those scripts run from the
merged SHA checkout.

---

## 2. Focused review — #109 restore safety

**Decision: PASS** as **unsafe-restore prevention**.
Real production recovery remains **BLOCKED**.

### Production refusal before decrypt/apply

`parkio_restore_refuse_unverified_production` in
`scripts/lib/restore-safe-preflight.sh` returns exit 3 unless
`parkio_restore_isolated_fixture_ok`. Wired in:

- `restore-hosted-beta.sh` after stamp preflight / snapshot-clock check
  (skipped only for `--dry-run`);
- `restore-database.sh` immediately after fixture accept, before dump apply.

A cutoff equal to or earlier than the declared stamp clock is still BLOCKED
in production. `verifiedCoverage` is always false.
`--supplemental-covered-through` does not certify coverage.
Evidence: `scripts/test-restore-safe-preflight.sh` cases
“incomplete ledger with new manifest timestamp stays BLOCKED” and
“equal cutoff without verified coverage stays BLOCKED”, plus
Observability CI `23 passed, 0 failed` on `f3bfe297`.

Additional production refusals (same helper, same fixture gate):

- standalone `restore-database.sh` → `parkio_restore_refuse_standalone_database`;
- `--only minio` → `parkio_restore_refuse_unsupported_production_scope`.

### Isolated fixture: what it does and does not prove

`--isolated-fixture` plus a stamp-bound ticket is the only supported
synthetic path. `PARKIO_RESTORE_ISOLATED_DRILL` and
`PARKIO_RESTORE_PREFLIGHT_DONE` alone cannot bypass
(`parkio_restore_isolated_drill` / `parkio_restore_preflight_done` both
require `parkio_restore_isolated_fixture_ok`). Evidence: env-flag cases
in `test-restore-safe-preflight.sh`.

Ticket contents: `parkio-isolated-fixture=1` and `stamp=<realpath>`.
`parkio_restore_accept_isolated_fixture` **issues** that ticket when the
flag is set and no ticket is supplied. The ticket binds the **selected
stamp directory**, not the restore target.

What actually constrains the destination to an isolated fixture: **nothing
in the ticket**. Container names, networks, and MinIO buckets remain the
caller’s live defaults (`parkio-postgres-*`, `parkio-minio`, …). The flag
is an operator/CI declaration. It is not proof that the target is isolated.
Do not treat `--isolated-fixture` as a production recovery method.

### Dry-run

`--dry-run` skips database apply, erasure replay, and the new production
refusals. Database decrypt/apply is not performed.

Residual (pre-existing layout, not expanded here):
`restore_minio` in `restore-hosted-beta.sh` can call
`parkio_backup_unseal_minio` (`openssl enc -d`) **before** the dry-run
echo when `minio.tar.gz.enc` exists. That is decrypt into a temp stage,
not apply. Smallest later correction: skip unseal when `DRY_RUN=1`.
Not a Stage A blocker for this prevention merge.

### Nine-file dependency inventory (git blob SHA-256 at `f3bfe297`)

| File | vs installed #105 / `origin/api` | SHA-256 |
|---|---|---|
| `scripts/restore-hosted-beta.sh` | replace (changed) | `d6f15ae012b01d07f879a11cd47571cbc239f8777efa45058763f3b5c3482bbe` |
| `scripts/restore-database.sh` | replace (changed) | `fe128b907390c1cf7931b990f002d0f9e5be9ff5caa633569be225fd85218d4d` |
| `scripts/lib/restore-safe-preflight.sh` | new | `25781a116c3d006c5ff11ab0c9f2bbe54c1c3be5dff138d0acb8ada48fdf8951` |
| `scripts/lib/restore-stamp-preflight.py` | replace (changed vs api) | `db312feba7117d7f568d02c1a35bbf8134631d3c80a7eff8e7107218932c23a9` |
| `scripts/lib/restore-erasure-ledger.py` | replace (changed vs api) | `e81a230804bd645107ebe69c24a17cbcc432d4171b149f43633a404951527256` |
| `scripts/lib/restore-client-compat.py` | **identical to api** — keep | `f946d8589b5a66a4fb55339ba2f815177d3684e41cee5979b901e8067e5e3f89` |
| `scripts/lib/restore-dump-profile.py` | **identical to api** — keep | `3008048b969b1f9ea921b0baa59687ebc4ca492f4a2cc40bf919a5b77450c8c2` |
| `scripts/lib/erasure-tombstones.sh` | **identical to api** — keep | `c1b36d2be52e09277d85636170c87daad1114d66d8ae2d67ca44db333ae3756b` |
| `scripts/lib/backup-common.sh` | **identical to installed #105** — do not overwrite | `69ff315af4280404fa78342ca8c3a2da3213d0ebd75e7cfa6338240b0644aea6` |

Runtime (later host probe, not authorized now): bash, python3, jq, openssl,
gzip, sha256sum, docker, identified `psql`. Do not treat CI psql 16.10 as a
16.15 dump.

---

## 3. Composition (#108 first, then #109)

Disposable check: `git merge-tree --write-tree` of
`1e14f3f3` with `f3bfe297` (merge-base `b5a86ed8`) and of each head onto
`origin/api`. All three produced trees, **no conflicts**.

Shared files: **none**. No shared helper is rewritten by both PRs.
`deploy-common.sh` is #108 only. Restore helpers are #109 only.
`backup-common.sh` is listed as a #109 dependency and is byte-identical to
`origin/api` / installed #105.

Behavioral conflict: **none**. Different entrypoints, different host file
sets.

No extra focused check was run. Existing evidence covers each change; the
composition does not create a new interaction.

### What CI can be reused vs must re-run

Reuse as **implementation evidence** (do not carry across a later code SHA):

- #108 `1e14f3f3`: 139-case guard test + required checks.
- #109 `f3bfe297`: 23-case restore preflight + 26-check set, including
  Restore drill 01 (helper path only).

Must run again after an eventual base refresh (strict required checks):

- Any merge or rebase of #108 or #109 that produces a new head:
  `Build & unit tests` and `Secret scan` on **that** head.
- After #108 merges to `api`, refresh #109 onto that `api` and re-run
  #109 affected checks: Observability `Config + script checks`,
  Restore drill 01, Backup restore drill.
- #108’s 139-case job re-runs if the merge commit is a new SHA that
  touches the invite-production workflow paths; it does not need to be
  re-run merely because #109 is refreshed, because the file sets do not
  overlap.

---

## Stage A — repository merges (later authorization required)

1. Undraft/merge **#108** to `api` at `1e14f3f3` (or a refreshed head that
   re-satisfies strict required checks). Normal merge. No protection bypass.
2. Refresh **#109** onto that `api`. Re-run required + affected restore
   checks on the refreshed head. Merge #109 only after those pass.
3. Do **not** merge, rebase, or enable #104.
4. Do **not** combine the two into one integration PR.

Host work is excluded from Stage A.

---

## Stage B — #108 host activation (later separate authorization)

Read-only inventory and compatibility first. Then check-only validation
against the accepted **local** web image:

`ghcr.io/adberilgen35/parkio/web@sha256:aacf9dc9ab8ef412dee01429da2b2bbc33099c4560a7904f181f9fe6db381f6e`

Expected check-only fields (do not infer host success from any prior
workstation/reference-only run):

| Field | Expected |
|---|---|
| Result | `PASS` |
| `requestedImage` / `bound` | the digest above |
| `configId` | `sha256:de405e587f8da14b70d8046d3ba9d14250ff8a705e3ffef8881d3d555d01d8be` |
| `platform` | `linux/amd64` |
| `fingerprintCheck` | `not-requested` |

Confirm the host’s **actual** local image identity (`docker image inspect`)
and that `config --hash web` is unchanged with the binding override.
Stop on any mismatch. Do not install. Do not use break-glass.

Install only the four files in section 1. `cp -a` / `install -p` backups under
`/var/backups/parkio-f05-<UTC>`. Preserve existing modes/owners
(wrapper `0755`, guard via `bash` so `0644` is acceptable; new lib files
`0644` owned like `scripts/lib/`). Verify post-image SHA-256. No host
`git pull` / `reset` / `clean`. No image, pin, env, or container change.
No web recreate.

Protected after this scoped install: Civo `parkio-prod-compose.sh`
web-creating `up` / `create` / `run`. Not protected: deploy/rollback
until those scripts run from a clean merged-SHA checkout
(`deploy-common.sh` is not copied).

Rollback restores the pre-image wrapper/guard and deletes the two new
files. That is **not** equivalent protection.

---

## Stage C — #109 host decision (later separate authorization)

Nine-file plan in section 2. Preserve installed #105: if host
`scripts/lib/backup-common.sh` is already
`69ff315af4280404fa78342ca8c3a2da3213d0ebd75e7cfa6338240b0644aea6`,
**do not overwrite it**. Same for the three api-identical helpers.
Replace only the five changed/new restore files after a pre-image
inventory and `cp -a` backups.

Production restore will be **refused** until a future supported
verified-coverage mechanism exists. That is the intended host effect.
Do not run a restore as an installation check.

Rollback restores the old unsafe path. It is not equivalent protection.

---

## Stage D — #105 scheduled-backup acceptance (separate follow-up)

First eligible run: **2026-09-25T03:30:00Z**. Do not observe it now and
do not start a watcher.

Later read-only acceptance checklist (after that run exists):

1. Run identity and result (scheduler id, start/end, exit).
2. `COMPLETE` present; expected databases listed; MinIO artifact present.
3. Ledger present; checksums match `SHA256SUMS`.
4. Fresh telemetry for that run (not a prior stamp).
5. Independently verified remote presence (separate from local `COMPLETE`).

Keep separate: remote presence, remote byte integrity, decryption, and
restore acceptance. Do not rewrite sealed stamps. Do not use the
pre-install 2026-09-24 stamp as #105 acceptance.

---

## 4. Decisions

| Question | Decision |
|---|---|
| #108 focused review | **PASS** at `1e14f3f3` |
| #109 focused review | **PASS** at `f3bfe297` as prevention, not recovery |
| Composition | **PASS** — #108 then #109; no conflicts |
| Repository merge readiness | Ready for a **later** Stage A authorization |
| Host install readiness | **No** — Stages B and C need separate authorization |
| Real production restore | **BLOCKED** — no verified coverage |
| #104 | **HOLD** |
| Concrete release blocker in this review | **None** that blocks Stage A. Residuals: isolated-fixture is not destination proof; dry-run can still unseal MinIO. |

### Next authorization decision

**Repository-only merges (Stage A): #108 then #109.**

Host inventory, host install, image/pin/env/container changes, backup
execution, download, decrypt, restore, and Stage D observation are
**explicitly excluded** from that next decision.
