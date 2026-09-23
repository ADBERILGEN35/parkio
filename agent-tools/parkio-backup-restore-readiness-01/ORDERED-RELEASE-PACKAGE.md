# Ordered backup readiness release package (#94 → #97 → #98)

**Prepared:** 2026-09-23. Preparation only. No PR was merged. No production
file was edited. Cron was not changed. No workflow was dispatched. No real
backup was executed, downloaded, or restored. Web and Alertmanager stay on
the accepted #91 / #100 deployments. Do not poll Alertmanager.

Default branch remains `master`. Do not change it. Do not merge `api`
wholesale into `master`.

## Current heads

| Ref | SHA | Notes |
|---|---|---|
| `origin/api` | `b7aaea39eeff38aed645c5d4ddbaad84efd40380` | Includes #99 and #100 |
| #94 reviewed | `84bae47ba556986a8d6e4c0bad7fe92ebe998412` | Prior reviewed head |
| #94 refreshed | `da0501e9aeb33fdcaa0c7a361cbf4e570c59eed4` | `api` merged at `3cb4bedb`, then docs/package. Needed because `api` protection is `strict` |
| #97 reviewed | `5414cf71af0d867f14e4ced64135f58636ba59a2` | Fail-closed scripts |
| #97 refreshed | `33ae2dc117210129a6477083b566f799ba0a02a8` | `api` merged; Alertmanager render checks kept |
| #98 | `af2552b0eee54f76801e15680579716d59054bf1` | Unchanged in this step |
| `origin/master` | `e5692428ac57462226f1f68cf4db0f4da0ef3ca6` | 2026-07-30; stale canary drill |

## 1. PR review against current targets

### PR #94 → `api` (repository-only)

- URL: https://github.com/ADBERILGEN35/parkio/pull/94
- Title: docs(ops): backup and restore readiness (BRR-01) and isolated restore drill 01
- Draft: no. Scope: docs, `restore-drill-01-procedure.yml`, synthetic evidence.
- Does **not** change backup scripts, cron, host files, or `master`.
- Mergeability of the reviewed head: **MERGEABLE / BEHIND**. `api` protection
  requires up-to-date branches (`strict: true`) and these required checks:
  `Build & unit tests`, `Secret scan`.
- Reviewed-head checks: all completed required/affected checks **SUCCESS**,
  including Encrypted stamp → isolated restore and Config + script checks.
  Invite-production deploy jobs were SKIPPED (correct).
- Dependency: none. This PR **must merge first** so
  `restore-drill-01-procedure.yml` exists on `api` before #98 can dispatch it.
  That file is **404 on `api` today**.
- Unrelated `api` work (#99/#100 Alertmanager, #91 web pin) is preserved by
  merging `api` into this branch, not the other way around.

### PR #97 → `api` (scripts + scoped host install later)

- URL: https://github.com/ADBERILGEN35/parkio/pull/97
- Title: fix(ops): fail-close production backups when dump or erasure ledger fails
- Draft: yes. Files: `scripts/backup-databases.sh`, `scripts/backup-hosted-beta.sh`,
  `scripts/lib/backup-common.sh`, `scripts/lib/erasure-tombstones.sh`,
  `scripts/test-backup-production-fail-closed.sh`,
  `scripts/test-invite-production-backup-scheduler.sh`, and workflow path/step
  hooks only.
- Reviewed-head checks: required/affected **SUCCESS** (unit, Config + script
  checks, Backup → restore → assert).
- Reviewed head vs current `api`: **CONFLICTING / DIRTY**. Sole textual
  conflict after merge was `.github/workflows/observability-validation.yml`
  (bash-syntax file list). Resolution keeps **both** #100 Alertmanager render
  tests and the fail-closed test. Other workflow files auto-merged.
- Dependency: merge after #94 so the isolated drill workflow is already on
  `api`. Script changes do not require #94 at runtime, but the ordered
  package keeps one sequence.
- `run-production-backup.sh` is **unchanged**. Do not overwrite it on the host.

### PR #98 → `master` (dispatcher only)

- URL: https://github.com/ADBERILGEN35/parkio/pull/98
- Title: ci: dispatch weekly restore drills onto `api` from the default branch
- Draft: yes. Files: new
  `.github/workflows/scheduled-restore-drills.yml`; remove `schedule:` from
  `master`'s `backup-restore-drill.yml`; keep `workflow_dispatch`.
- Mergeable: **MERGEABLE / UNSTABLE**.
- Checks: Backend unit / CodeQL / secret scan / Trivy **SUCCESS**.
  `Backup → restore → assert` **FAILURE** (stale `master` path, no
  `postgres-gateway` — the defect being retired). Container scans and
  Security CI summary **FAILURE** on this `master` base. Those are unrelated
  to the two workflow files and are **not** a reason to merge `api` into
  `master`.
- `master` has no branch protection.
- Dependency: **#94 must be merged to `api` before any dispatch** of
  `restore-drill-01-procedure.yml --ref api`.

## 2. #98 dispatcher (source / configuration only)

Verified in `.github/workflows/scheduled-restore-drills.yml` at `af2552b0`.
No `workflow_dispatch` was started.

| Check | Result |
|---|---|
| Permissions | `actions: write`, `contents: read`. Sufficient for `gh workflow run` with `GITHUB_TOKEN`. |
| Triggers | `schedule: 23 4 * * 1` and `workflow_dispatch`. |
| Target 1 | `gh workflow run backup-restore-drill.yml --ref api` — file **exists on `api`**, has `workflow_dispatch`. |
| Target 2 | `gh workflow run restore-drill-01-procedure.yml --ref api` — file **missing on `api` until #94 merges** (HTTP 404). |
| Ref selection | `--ref api` is correct. The run uses `api`'s workflow YAML and tree. |
| Default branch | Unchanged. Only these two workflow files go to `master`. |
| Downstream failures | Dispatcher `timeout-minutes: 10` and only starts the workflows. It does **not** `gh run watch`. A failed drill is visible on the **api** run, not on the schedule job. Adding watch needs ≥45 minutes timeout. |
| How to confirm later | After #94 and #98: Actions → Scheduled restore drills → Run workflow. Then confirm two new `api` runs at `api` HEAD. Do not do that in this step. |

## 3. Corrected operational facts (now in BRR-01)

- Slack data directory `/var/lib/parkio/slack-biz` is **located**. SQLite file
  existence was **not** established because directory access was restricted.
- NR `budget.db` **exists** on the persistent host bind
  `/var/lib/parkio-nr-log-continuous/budget` →
  `/var/lib/parkio-nr-budget/budget.db`.
- Both operational stores remain **excluded** from consistent backups.
- #99 restored genuine Alertmanager Slack **delivery**. #100 presentation is
  deployed (`b7aaea39`). New-message **visual** acceptance remains awaiting.
  Do not redeploy or poll Alertmanager.
- PostgreSQL **server**, **client**, and **PostGIS** are recorded separately
  (live gateway server 16.15 vs CI restore-client 16.10 / target-server 16.4 /
  PostGIS 3.4.3). Live parking server and live PostGIS remain UNKNOWN.

## 4. Ordered rollout (authorize each step)

### Step A — #94 repository-only merge

1. Confirm the refreshed #94 head is up to date with `api` and required
   checks are green.
2. Merge #94 to `api` with the normal merge button. No host action.
3. Confirm `.github/workflows/restore-drill-01-procedure.yml` exists on `api`
   (`workflow_dispatch` present).

Rollback: revert the merge commit on `api`. Nothing was installed.

### Step B — #97 merge, then scoped host script install

1. Confirm #97 `33ae2dc1` (or later) is MERGEABLE against the post-#94 `api`
   and required checks are green.
2. Merge #97 to `api`. Still no host action until the install below is
   separately authorized.
3. **Scoped install only** (when authorized). Do **not** `git pull` / `reset`
   `/opt/parkio`. Preserve COMPLETE stamps and secrets.

**Host files to replace** (exactly these four):

| Host path | #97 blob SHA-256 (`5414cf71` / unchanged by the merge) |
|---|---|
| `/opt/parkio/scripts/backup-databases.sh` | `63f4d3f546acc400bd6b3948d515ca6f134c299c764c79d6e7ee6769208b625d` |
| `/opt/parkio/scripts/backup-hosted-beta.sh` | `d6223c63ce711995927eaf3cbc88ec5b85eeafbe04bfcfe43030886c79038aad` |
| `/opt/parkio/scripts/lib/backup-common.sh` | `5da2837e47d0251b08b3964c3555bfb120ffbb9c82166a102f62ba34d6a7ddfd` |
| `/opt/parkio/scripts/lib/erasure-tombstones.sh` | `c1b36d2be52e09277d85636170c87daad1114d66d8ae2d67ca44db333ae3756b` |

**Do not install on the host:** test scripts, workflow files,
`run-production-backup.sh`.

**Pre-install backup** (example; stamp chosen at install time):

```bash
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
DEST=/home/civo/parkio-fu1-rollback/$STAMP
mkdir -p "$DEST"
for f in \
  scripts/backup-databases.sh \
  scripts/backup-hosted-beta.sh \
  scripts/lib/backup-common.sh \
  scripts/lib/erasure-tombstones.sh
do
  sudo cp -a "/opt/parkio/$f" "$DEST/$(basename "$f")"
  sudo stat -c '%a %U:%G %n' "/opt/parkio/$f" >> "$DEST/PRE-INSTALL.stat"
  sha256sum "/opt/parkio/$f" >> "$DEST/PRE-INSTALL.sha256"
done
sudo cp -a /etc/cron.d/parkio-backup "$DEST/parkio-backup.cron" || true
sha256sum /etc/cron.d/parkio-backup >> "$DEST/PRE-INSTALL.sha256" || true
```

Copy the four reviewed files onto the host with the **existing** owner/group
and mode `0755` (confirm with `stat` first; do not invent a new owner).

**Cron / lock (leave unchanged):**

```text
# /etc/cron.d/parkio-backup
30 3 * * * root flock -n /var/lock/parkio-backup.lock \
  /opt/parkio/scripts/run-production-backup.sh >> /var/log/parkio-backup.log 2>&1
```

Lock file: `/var/lock/parkio-backup.lock`. Do not delete it. Do not add a
second cron entry. Do not run the nightly job as part of install.

**Validation of the install** (not a real backup):

- Each of the four host files matches the SHA-256 table above.
- `stat` shows `0755` and the pre-install owner/group.
- `bash -n` on each installed file.
- `/etc/cron.d/parkio-backup` SHA-256 equals the pre-install copy.
- Existing `COMPLETE` stamps under the configured `BACKUP_DIR` (default
  `/opt/parkio/backups` unless the host env overrides it) are still present.
  List only. Do not download, decrypt, or restore.
- Secrets (`/opt/parkio/docker/.env.azure-hosted-beta`,
  `/etc/parkio/*.secret.env`) are untouched.

**Installing the fix ≠ a successful real backup.** A later authorized nightly
or one-shot `run-production-backup.sh` is the only observation that the new
fail-closed path wrote a usable `COMPLETE` stamp.

**Rollback:** copy the four files from `$DEST` back with `cp -a`. Do not
touch cron, stamps, or secrets.

### Step C — #98 merge to `master`, then synthetic dispatch

1. Merge #98 to `master` only after Step A. Touch only the two workflow files.
2. Manually run **Scheduled restore drills** once (`workflow_dispatch` on
   `master`). Confirm two new `api` runs at the then-current `api` HEAD.
3. Treat a red `api` drill as a failed acceptance even if the dispatcher job
   is green (it does not watch).
4. Leave the Monday schedule to fire on its own. Do not change the default
   branch.

Rollback: revert the #98 merge on `master`. The stale `master` canary schedule
would return until that revert's `backup-restore-drill.yml` is cleaned again.

## 5. Blockers

1. #94 must be green and up to date with `api` after the refresh/docs push
   (`strict` protection).
2. #97 must stay MERGEABLE after #94 lands; re-check if `api` moves again.
3. #98 cannot dispatch `restore-drill-01-procedure.yml` until #94 is on `api`.
4. #98 PR checks will stay red on the stale `master` canary and `master`
   container scans. Do not “fix” those by merging `api` into `master`.
5. Slack and NR operational stores are still outside consistent backups.
6. #100 new-message visual acceptance is still awaiting. Not a backup blocker.
   Do not redeploy Alertmanager.
7. Host `/opt/parkio` remains drifted at `bf9cad51` **[OBS 2026-09-22]**.
   Broad git reconciliation is **out of scope**.

## 6. Actions awaiting authorization

Do **not** do these until explicitly authorized:

1. Merge #94 to `api`.
2. Merge #97 to `api`.
3. Scoped install of the four host backup scripts (backup, checksum, copy,
   validate, no cron change).
4. Merge #98 to `master`.
5. Synthetic `workflow_dispatch` of Scheduled restore drills.
6. Any real backup execution, offsite download, or restore.
7. Adding `gh run watch` to #98 (optional visibility follow-up).
8. #100 visual sign-off (operator, natural Slack page).

## 7. Tests reused / run

- Reused: #94 reviewed-head required checks (restore drill 01
  `35888589173` PASS; Config + script checks; unit; secret scan).
- Reused: #97 reviewed-head required checks on `5414cf71`.
- Reused: #98 source/config review; no new dispatcher run.
- After the #94 docs commit and #97 refresh push: run only the affected
  required checks on those new heads (`Build & unit tests`, `Secret scan`,
  plus path-filtered Config + script checks / fail-closed where the
  workflows apply). Do not re-run container scans or invite-production.

## 8. Out of scope (this step and the later install)

- Web pin, gateway, auth, parking, Slack relay restart, New Relic restart.
- Alertmanager recreate or template edit.
- Default-branch change.
- Host git reconciliation.
- Secret retrieval.
- Treating lingering municipal Alertmanager groups as a new İZUM outage.
