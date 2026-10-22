# N-02 — Real GCS provider acceptance (#483): plan, harness and execution proposal

**Status (2026-10-22):** harness and operator script independently reviewed and re-reviewed (three
reviewers; findings addressed in #491); **nothing provisioned**. No project, bucket, IAM change, retention lock
or upload has been made. §7 is the exact operation awaiting owner approval.
**Parent:** #365 · **Adapter:** #482 / #484 · **Defect fixed here:** #492 · **Scope:** real-provider
acceptance (layer C) only.
**Data:** synthetic only. **Production retention duration:** remains unset. **Bucket Lock:** out of scope.

## 1. Scope

| In scope | Out of scope |
|----------|--------------|
| The merged adapter and store against one dedicated, non-production Cloud Storage bucket with Object Retention Lock | Production export, enablement, exposure or legal retention (#365, layer D) |
| Synthetic evidence written by the harness; short test retention | Bucket Lock (bucket retention policy) on any bucket |
| Machine-readable, sanitized evidence tied to the source SHA and the bucket identity | Emulators, fakes and the offline model: they **do not** satisfy #483 |
| | A production-readiness claim: the run is bounded and small (#493, #494 stay open) |

## 2. What the run exercises

The harness (`GcsAcceptanceTool`, test classpath only) drives the merged classes, not equivalent `gcloud` calls:

- `GcsObjectRetentionBucket` over `GoogleCloudStorageTransport`, built the way `GcsDurableStoreConfig`
  builds it (JSON API over HTTP, default retry settings: 6 attempts within 50 s; default timeouts:
  20 s connect and 20 s read per request). Only the credentials differ: keyless Application Default
  Credentials impersonate one service account per identity.
- `ObjectLockDurableErasureRecordStore` (putIfAbsent, findByRequestId, publishCheckpoint) on that bucket,
  confined to the run's namespace `runs/<run-id>/store/` by `PrefixedEvidenceBucket`.
- `ObjectLockEvidenceObjects`, `DurableErasureEvidenceVerifier.recover` and `EvidenceBundle`: the existing
  verifier, run locally over bytes the read-only identity read from the live bucket.
- Raw client calls only where the adapter has no operation: bucket metadata, shorten / clear / change
  retention (also addressed to one generation with the JSON API's `generation` parameter), delete a
  generation, and an insert without retention (IAM probe).

Not exercised live: Spring wiring of `GcsDurableStoreConfig` (covered by `GcsDurableStoreConfigTest`),
several auth instances writing one bucket (outside the store's contract), Bucket Lock.

## 3. Provider facts that shape the run

Read from Google documentation on 2026-10-22; the run confirms or refutes each one.

| Fact | Consequence | Source |
|------|-------------|--------|
| Object Retention Lock is enabled at bucket creation (`--enable-per-object-retention`); existing buckets only in the console; it cannot be disabled; a best-effort lien is placed on `projects.delete` | Enable it on a new, dedicated bucket only; record the lien after creation and remove only that lien | [object-lock](https://docs.cloud.google.com/storage/docs/object-lock), [using-object-lock](https://docs.cloud.google.com/storage/docs/using-object-lock), [liens](https://docs.cloud.google.com/resource-manager/docs/project-liens) |
| Locked: retain-until can only increase; mode cannot change. Unlocked: shorten / remove / lock needs `overrideUnlockedRetention=true` | K and G cases | same |
| A retained object cannot be deleted or replaced; with versioning a retained live object can still become noncurrent | Versioning is required (the store rewrites the frontier); P3 proves the noncurrent generation stays Locked | object-lock |
| `objects.insert` needs `storage.objects.create`, `setRetention` when the body carries retention, `delete` when it overwrites a name; `objects.patch` needs `update`, `setRetention`, `overrideUnlockedRetention` when overriding; `objects.delete` needs `delete`; none needs `objects.get` | Writer role holds `delete` (L2 measures the overwrite rule); the probe admin has no `objects.get` | [iam-json](https://docs.cloud.google.com/storage/docs/access-control/iam-json) |
| `objects.patch` takes a `generation` parameter (a specific, also noncurrent, revision); the Java client never sends it | Probes patch the live object under `ifGenerationMatch`; K8/K9 send `generation=` through the JSON API client | iam-json, client source 2.60.0 |
| About one write and one metadata update per second per object name; faster writes "might" get 429; bucket create/delete about one per 2 s per project | The adapter resends a write refused with 429 (#492); T1 and S5 measure real throttling | [quotas](https://docs.cloud.google.com/storage/quotas) |
| An insert without precondition and a PATCH without `ifMetagenerationMatch` are not retried by the client; gets, lists and generation deletes are | HTTP requests can exceed logical operations only through client retries, and the gate counts each one | client source 2.60.0 |
| IAM allow-policy changes take typically 2, potentially 7 minutes or longer | The run starts at least 7 minutes after the last binding | [access-change-propagation](https://docs.cloud.google.com/iam/docs/access-change-propagation) |
| Revoking an OAuth token revokes the grant of that client: "invalidating any issued access or refresh tokens for all clients registered under that project" | No step revokes; the operation's own credentials live only in a dedicated directory that is deleted at the end | [token revocation](https://developers.google.com/identity/protocols/oauth2/native-app#tokenrevoke) |
| Requests answered 4xx/5xx are generally not charged; deletes are free; general network egress $0.12/GiB | Negative cases cost nothing; egress ≈ 2 MiB per run | [pricing](https://cloud.google.com/storage/pricing) |

The rows marked "client source 2.60.0" hold for the approved commit only while it pins google-cloud-storage
2.60.0 (dependabot #485 proposes 2.75.0). Another client version reopens those rows and the request-shape test
before a live run.

## 4. Corrections

### 4.1 To the first version of this plan

| Previous | Corrected | Why |
|----------|-----------|-----|
| ~20–40 API calls | **870 logical operations** per run (§7.6) | ≥ 3 pages at the adapter's page size 100 need 205 generations; the store lists markers and frontier generations on each put; concurrency, resends, negative cases, verification and export |
| IAM: create / get / list + "retention get/set" | Six custom roles (§7.4) | Retention on insert needs `setRetention`; overwrite needs `delete`; PATCH needs `update` (+ override); impersonation needs only `getAccessToken` |
| COMPLIANCE retain-until 24 h | **2 h** (§7.7) | No case needs an overnight re-check; the cleanup after expiry is the re-check |
| Versioning assumed | Required, checked by the preflight **and** by the adapter's discovery | Object Retention Lock does not imply versioning (unlike S3 Object Lock) |

### 4.2 After the independent reviews of #491 (2026-10-22)

| Finding | Change |
|---------|--------|
| S5 was a measurement, so a paced S5b could make the run PASS while the production path failed | S5 is a required assertion (one unpaced production call); S5r proves retry convergence; S5b is a diagnostic that never counts; three separate facts in the result (§6) |
| The production path failed under strict throttling (#492) | The adapter resends a write refused with 429; regression tests red before, green after |
| C1 could pass without a race | C1 needs ≥ 2 committed writers (up to three rounds) and checks every writer's view of the canonical generation |
| Refusals were a blacklist (408/409 counted) | Whitelist: 403 for deletes and inserts, 400 or 403 for retention patches with unchanged state; positive controls use the same request shapes |
| Caps were checked only between operations; token traffic uncounted | Every HTTP connection (storage, client retries, token refreshes) passes a gate that refuses after a stop, at the request cap or past the deadline; generation caps count every write that may have created a generation |
| No result after Ctrl-C, an error or a hang; lost responses missing from the inventory | `run.json` and an append-only journal before the first case; result written once by the main path, a shutdown hook or a watchdog; unanswered writes stay in the inventory as `unknown` |
| Operation used the default gcloud/ADC state and ended with a grant-wide revoke | Dedicated `CLOUDSDK_CONFIG`; no revocation; the directory is deleted at the end |
| Lien and project deletion guarded only by a shell variable | `scripts/n02-gcs-acceptance-op.sh` records project number, creation time, labels and the lien baseline and checks them before every destructive step; the project is deleted by number |
| Project-level and billing changes were not listed | Inventory with permissions in §7.4 |

### 4.3 After the re-review of b36115fd

| Finding | Change |
|---------|--------|
| Cleanup could never finish against real gcloud: a recursive listing prints the bucket itself, and `rm` without `--recursive` fails when nothing matches | Generations are counted from the JSON listing (`cloud_object` entries only); `rm` runs only when there are some; the stub gcloud now behaves like 590.0.0 |
| A failed bucket describe counted as "gone"; the lien and project could go while a bucket remained | Buckets come only from a successful listing; every bucket and the lien baseline are checked before anything is deleted; the listing must be empty before the lien and the project |
| A failure after the irreversible bucket left provisioning stuck | `provision` resumes on the recorded project and creates only what is missing; 60 s wait after creating service accounts, bounded retries for roles and bindings, polling for the lien |
| Only `run` was pinned to the approved commit; other gcloud overrides were not refused | `provision`, `verify`, `run` and `cleanup` require `N02_APPROVED_SHA` = HEAD and log it; every `CLOUDSDK_*` variable except `CLOUDSDK_CONFIG` is refused |
| An answer that changed nothing could count as a refusal or as a violation | A violation needs a changed (or unreadable) Locked generation, whatever the answer; an accepted answer without effect is inconclusive; K8c is a same-shape positive control, and K8/K9 become measurements when the provider does not apply generation-addressed retention changes |
| Retry convergence only covered the resumable path | S5s: a lost response after the checkpoint object committed; the next call takes a new sequence and the frontier covers both |
| L5 inserts bypassed the write ledger; a forced verdict could hide a violation; the endpoint lookup made one ungated request | Fixed; library versions recorded |

## 5. Cases

Live evidence unless marked. Every write and read uses the adapter; probes are raw client calls.

| Id | Identity | Passes when |
|----|----------|-------------|
| F5 | reader | Preflight: both buckets in the approved project (number) and location, STANDARD, UBLA, PAP enforced, versioning on, soft delete reported and off, no retention policy, no HNS, labels; ORL on the test bucket only. **Nothing is written unless F5 passes** |
| F1, F2 | reader, writer | `requireRetentionFeature` passes |
| F3 | reader | Refused on the control bucket because Object Retention Lock is missing (message checked, not IAM) |
| F4 | reader | Refused (fail-closed) on an absent bucket, provider 404/403 |
| F6 | writer-nodelete, probe-admin | Both identities authenticate and read the bucket; F1, F2 and F6 must pass before any write |
| P1, P2 | writer | GOVERNANCE / COMPLIANCE put; reported mode and retain-until equal the request; bytes by generation. A different lock stops the run (`lockMismatch`) |
| P3 | writer | Overwrite of a Locked live object adds a higher generation; the first stays canonical and Locked |
| H1 | writer | 205 sequential writes (1.1 s apart) get strictly increasing generations |
| H2 | reader | `versionCount`, `oldest`, `allVersions` see all 205 across ≥ 3 pages each; every generation holds its own write |
| H3 | reader | Transport paging at 32 per page: same set, no duplicates, ≥ 7 pages; prefix key listing complete |
| C1 | writer | 8 writers released together on one name (contention through the adapter's resends), each puts then reads the canonical version: ≥ 2 committed (else a fresh round, at most three), every listed generation accounted for, all writers read the same canonical generation, it is the lowest listed, exactly one writer reads its own; first-send commits and commit spread recorded |
| C2 | writer | An identical body twice gives two generations; the first stays canonical |
| T1 | writer | 10 back-to-back writes to one name through the adapter all complete; the provider's 429s are counted |
| S1–S3 | writer | Store put: COMPLIANCE receipt on the canonical generation; identical retry settles without a write; different body is a conflict without a write |
| S4 | writer | Response lost **after** the provider committed the record: not durable before the frontier covers it; the retry settles on that generation without a second write |
| S5 | writer | **One** unpaced `publishCheckpoint` call, as production makes it, completes; the frontier covers it, no gap or abandoned reservation (verifier) |
| S5r | writer | Retry convergence: after a failed S5, unpaced retries must converge; after a passing S5, the response to the next reservation marker is lost after commit and the next call must fill that reservation; no gap, no abandoned reservation |
| S5s | writer | After a passing S5: the response to the checkpoint object itself is lost after commit (no resume path, #365 finding 3); the next call takes a new sequence, the frontier covers both, sequences consumed recorded |
| S5b | writer | Diagnostic, only after a failed S5: one call with writes to one name spaced 1.1 s. Never counts as a pass |
| S6 | writer | A further record converges under the per-object limit; frontier history recorded |
| V1 | reader | Local verifier over the live store: ACCEPT_ISOLATED, no gaps, no abandoned reservations, boundary = highest sequence (local verifier, live bytes) |
| V2 | reader | Exported bundle verifies with the identical verdict; bundle and SHA-256 kept (local verifier, live bytes) |
| L1 | reader | Write with retention refused 403; nothing created |
| L2 | writer-nodelete | Measurement: a versioned overwrite without `storage.objects.delete` is refused 403 (any other failure is inconclusive) |
| L3, L4 | writer | Cannot delete a retained GOVERNANCE generation, cannot shorten it even with override |
| L5 | reader, probe-admin | An insert **without** retention is refused 403 for both; nothing created |
| K1–K5 | probe-admin | Locked: shorten, remove, switch to Unlocked, delete live, delete noncurrent — all refused, state unchanged |
| K6 | probe-admin | Positive control: Locked retain-until extended by 60 s |
| K7 | probe-admin | A record the store published under COMPLIANCE cannot be deleted |
| K8c | probe-admin | Measurement and positive control for K8/K9: a PATCH addressed to the noncurrent Locked generation extends its retention by 60 s |
| K8, K9 | probe-admin | A PATCH addressed to the noncurrent Locked generation cannot shorten or remove its retention. If K8c showed the provider does not apply addressed retention changes, K8/K9 are measurements that still require an unchanged generation |
| G1–G4 | probe-admin | GOVERNANCE: delete refused; shorten without override refused; with override shortened, removed, then deleted |
| G5 | probe-admin, writer | Positive control for L3: after the admin removes GOVERNANCE retention, the writer deletes the generation |

A refusal counts only with an explicit refusal code (403 for deletes and inserts; 400 or 403 for retention
patches, whose positive controls K6, G3, G4 and K8c use the same request shapes) **and** an unchanged
generation; an accepted answer that changes nothing, or any other code, is an ERROR, never a pass. Any change
of a Locked generation (or a state that cannot be read back), whatever the provider answered, stops the run at
once (`lockedMutationAccepted`, exit code 5) and the operation is not repeated (contingency route C,
`n02-gcs-evidence-store-compatibility.md`).

## 6. Harness

```bash
# offline plan of the full sequence (no network, not evidence)
./gradlew --no-daemon --max-workers=1 :services:auth-service:gcsProviderAcceptance --args="plan <empty dir>"
# live run: only through scripts/n02-gcs-acceptance-op.sh run (§7.9)
```

- Settings: `N02_GCS_PROJECT`, `N02_GCS_PROJECT_NUMBER`, `N02_GCS_LOCATION`, `N02_GCS_BUCKET`,
  `N02_GCS_CONTROL_BUCKET`, `N02_GCS_WRITER_SA`, `N02_GCS_WRITER_NODELETE_SA`, `N02_GCS_READER_SA`,
  `N02_GCS_PROBE_ADMIN_SA`, `N02_GCS_CONFIRM` (must repeat the bucket name), `CLOUDSDK_CONFIG`. No secrets.
- Refusals before any provider call: names outside `parkio-n02-orl-accept-<yyyymmdd>-<hex6>` /
  `parkio-n02-noorl-ctrl-<same>`, accounts outside the project, a shared account, a missing project
  number or `CLOUDSDK_CONFIG`, `GOOGLE_APPLICATION_CREDENTIALS` or `GOOGLE_SDK_JAVA_LOGGING` set, a build
  input with a tracked change **or an untracked file** (`git status --porcelain --untracked-files=all` over
  `services/auth-service`, `buildSrc`, `platform`, `gradle`, `gradlew` and the root build files), an
  unknown SHA, a non-empty output directory. ADC must be a user login, workload identity federation or the
  metadata server; a service-account key (also as the source of an impersonation file) is refused.
- Hard stops: 1,500 logical operations, 1,800 HTTP requests (storage requests, client retries and token
  refreshes; the connection is refused before anything is sent), 360 generations and 40 COMPLIANCE
  generations that may exist (acknowledged, unanswered or in flight; reserved before the write), 45 min.
  After the deadline nothing is sent; a request in flight ends within its timeouts; a watchdog writes the
  result and ends the JVM 2 min after the deadline.
- Before the first case: `run.json` (run id, namespace, start, deadline, `latestRetainUntilBound` = deadline
  + 2 h) and `journal.jsonl` (every write before it is sent with its requested retain-until, every
  acknowledged or unanswered write, retention changes, case results, stops), flushed per line.
- Result: `result.json` written exactly once — at the end, after an internal error (`ERROR`), from a
  shutdown hook (`INTERRUPTED`) or from the watchdog (`ABORTED`) — with the source SHA/tree and build-input
  status, client and endpoint description, the bucket snapshot incl. project number, identities, per-case
  evidence and operations, per-criterion status, the checkpoint facts, violations, budget, list-price cost
  and the cleanup inventory (unanswered writes as `generation: unknown`). Plus the synthetic `bundle.json`
  and `SHA256SUMS` over all files. Provider messages are sanitized; library logging goes to stderr at INFO
  (Google libraries at WARN); the synthetic HMAC key exists only in memory.
- `checkpointUnderThrottling` keeps the three facts apart: `normalPathCompleted` (S5) with
  `normalPathThrottledResponses`, `externalPacingRequired` (`no`, or from S5b only after a failed S5),
  `retryConvergedWithoutLoss` (S5r), plus the burst T1.
- Verdict: PASS only when every PREFLIGHT and ASSERTION case passed and nothing stopped the run;
  MEASUREMENT cases record a fact; DIAGNOSTIC cases never count. Exit codes: 0 PASS, 1 FAIL, 2 refused,
  3 ABORTED/INTERRUPTED, 4 ERROR, 5 FAIL with a safety violation. `countsFor483` is true only for a live PASS.
- Local tests (not evidence): `GcsAcceptanceToolTest` (31: offline sequence with headroom, one case per
  modelled provider defect (17), not-applicable addressed PATCH, concealment, every cap, harness error, stop
  while blocked, a stop never hides a violation, inventory, secrets, refusals), `LiveGcsAcceptanceProviderRequestShapeTest` (12: what the
  real client sends, resend, generation-addressed PATCH, gate, keyless credentials),
  `GcsObjectRetentionBucketTest` (13) and `scripts/test-n02-gcs-acceptance-op.sh` (60, a fake gcloud that
  behaves like 590.0.0). Mutation records: agent-tools evidence of #483.

## 7. Execution proposal (awaiting owner approval)

### 7.1 Project

A **new dedicated project** `parkio-n02-accept-5c2c7f`, linked to the owner's billing account, used for
nothing else and deleted at the end. Project IDs are never reusable; if the ID is taken at creation the
script stops before any change and a new suffix needs a new approval. An existing project is **not**
covered: the script creates and deletes its own project only.

### 7.2 Region

`europe-west4` (Netherlands): same jurisdiction as the approved offsite backup (Azure `westeurope`), the
lowest EU regional Standard price ($0.020/GiB-month, shared with europe-west1 and europe-north1), single
region (no replication charges).

### 7.3 Buckets and features

| | Test bucket | Control bucket |
|---|---|---|
| Name | `parkio-n02-orl-accept-20261022-5c2c7f` | `parkio-n02-noorl-ctrl-20261022-5c2c7f` |
| Location / class | europe-west4, STANDARD | same |
| Object Retention Lock | **enabled at creation (irreversible)** | off |
| Versioning | on | on |
| Soft delete | off at creation (`--soft-delete-duration=0`) | off |
| UBLA / public access prevention | on / enforced | on / enforced |
| Retention policy (Bucket Lock), lifecycle, Autoclass, HNS, CMEK | none | none |
| Labels | `parkio-purpose=n02-gcs-acceptance`, `parkio-data=synthetic`, `parkio-issue=483` | same |

### 7.4 Changes by level, with the permission each needs

| Level | Change | Permission (role that has it) |
|-------|--------|-------------------------------|
| Organization / account | Create project `parkio-n02-accept-5c2c7f` (no parent unless the account's organization requires one; labels `parkio-purpose`, `parkio-issue`; no default APIs) | `resourcemanager.projects.create` on the parent; without an organization the account's project quota |
| Project | The creator becomes `roles/owner` (automatic) | — |
| Billing account | Link the project to the owner's billing account | `billing.resourceAssociations.create` on the billing account (Billing Account User/Administrator) **and** `resourcemanager.projects.createBillingAssignment` on the project (Owner) |
| Project | Enable `storage`, `iam`, `iamcredentials`, `cloudresourcemanager` APIs | `serviceusage.services.enable` (Owner) |
| Project | Create both buckets; Object Retention Lock on the test bucket (places the lien) | `storage.buckets.create`, `storage.buckets.enableObjectRetention` (Owner) |
| Project | Create 4 service accounts, **no keys**: `n02-writer`, `n02-writer-nodelete`, `n02-reader`, `n02-probe-admin` | `iam.serviceAccounts.create` (Owner) |
| Project | Create 6 custom roles (definitions grant nothing by themselves), below | `iam.roles.create` (Owner) |
| Bucket | Test bucket: each account → its role; control bucket: `n02-reader` → `n02BucketMetadataReader` | `storage.buckets.getIamPolicy` / `setIamPolicy` (Owner via the default bucket binding) |
| Service account | On each of the 4: the operator's user → `n02TokenMinter` (impersonation only) | `iam.serviceAccounts.getIamPolicy` / `setIamPolicy` (Owner) |
| ADC (dedicated directory) | Quota project of the operator's ADC = this project | `serviceusage.services.use` (Owner) |
| Read-only checks | List and describe billing accounts; bucket updates; list service-account keys | `billing.accounts.get/list` (Billing Account User); `storage.buckets.update` (Owner via the default bucket binding); `iam.serviceAccountKeys.list` (Owner) |

| Custom role | Permissions |
|-------------|-------------|
| `n02EvidenceWriter` | storage.buckets.get, storage.objects.create, storage.objects.delete, storage.objects.get, storage.objects.list, storage.objects.setRetention |
| `n02EvidenceWriterNoDelete` | the writer role without storage.objects.delete (a deliberate mirror, so L2 isolates `delete`) |
| `n02EvidenceReader` | storage.buckets.get, storage.objects.get, storage.objects.list |
| `n02RetentionProbeAdmin` | storage.buckets.get, storage.objects.delete, storage.objects.overrideUnlockedRetention, storage.objects.setRetention, storage.objects.update |
| `n02BucketMetadataReader` | storage.buckets.get (control bucket) |
| `n02TokenMinter` | iam.serviceAccounts.getAccessToken |

Nothing is granted at project or organization level besides the creator's automatic Owner. If a step is
refused (for example `enableObjectRetention`), the script stops; no broader grant is improvised. Cleanup
needs `storage.objects.list/delete`, `storage.buckets.delete`, `iam.serviceAccounts.delete`,
`iam.roles.delete`, `resourcemanager.projects.updateLiens` and `resourcemanager.projects.delete` (Owner).

### 7.5 Credentials

- Google Cloud CLI 590.0.0 is installed for the operator user from Google's Linux archive
  (`google-cloud-cli-linux-x86_64.tar.gz`, SHA-256
  `351ed4a2812c5525c1cee00334257a42c020fcaffa27d3067c1eec0a618dd83f` as published on the install page on
  2026-10-22, `install.sh --path-update=false`); `sudo` is not available for the apt package. A reinstall
  stops if the rapid channel has moved past 590.0.0 (the versioned archive has a different hash).
- Everything runs with `CLOUDSDK_CONFIG=$HOME/.config/gcloud-parkio-n02-483`; the default `~/.config/gcloud`
  is never read or written, no configuration is created or activated, `core/project` stays unset and every
  command passes `--project`. google-auth-library reads ADC from the same directory.
- Two interactive logins by the owner, in that directory only: `gcloud auth login --no-launch-browser`
  (before provisioning) and `gcloud auth application-default login --no-launch-browser` +
  `gcloud auth application-default set-quota-project parkio-n02-accept-5c2c7f` (after provisioning).
- No step revokes a token (Google revokes the whole grant, which would also sign the account out of
  gcloud/ADC elsewhere). `forget-credentials` deletes only the dedicated directory; deleting it does not
  invalidate the tokens at Google.
- The owner's Owner-level refresh tokens stay in that directory on this shared workstation until
  `forget-credentials`. To keep the window short: `forget-credentials` right after the last `run`, and a fresh
  `gcloud auth login` in the same directory for the cleanup, then `forget-credentials` again. If the account
  uses gcloud/ADC nowhere else, the owner can also revoke "Google Cloud SDK" and "Google Auth Library" in the
  Google account settings after the cleanup.

### 7.6 Synthetic data and limits

Harness-generated JSON only (`parkio-n02-acceptance-synthetic`, random UUIDs, synthetic database identity,
HMAC key generated in memory per run). Never production evidence, trust documents or credentials.

| Per run | Cap | Offline plan |
|---------|-----|--------------|
| Logical operations | 1,500 | 942 (487 Class A, 410 Class B charged; 46 refused or throttled) |
| HTTP requests (incl. retries, tokens) | 1,800 | ≈ 942 plus a few token requests |
| Generations that may exist | 360 | 258 (229 GOVERNANCE, 26 COMPLIANCE, 3 unanswered) |
| COMPLIANCE generations that may exist | 40 | 29 |
| Wall clock | 45 min | ≈ 4.7 min of waits plus request latency |

At most **two attempts** under this approval. An attempt counts once it has sent a write; an attempt that
stops in the preflight (nothing written) does not count, and after three such stops the operator reports
instead of retrying. A second attempt only after a diagnosed failure.

### 7.7 Retention and irreversible consequences

- GOVERNANCE (Unlocked): run start + **2 h** (adapter cases). COMPLIANCE (Locked): run start + 2 h for P2/P3
  (K6 adds 60 s to one), write time + 2 h for store records. The latest requested retain-until is in
  `result.json` (`latestRetainUntil`; offline plan ≈ start + 2 h 5 min); the hard bound is the deadline +
  2 h = **start + 2 h 45 min**.
- Why 2 h: the run is capped at 45 min and the Locked refusals run last, so every Locked object stays
  retained through the last probe with more than an hour of margin, and the irreversible window is short.
- Irreversible: Object Retention Lock can never be disabled on the test bucket; nobody (project owner
  included) can shorten, remove or delete a Locked generation before its retain-until; while the
  best-effort lien exists the project cannot be deleted; retained objects are billed until deleted.

### 7.8 Cost and stop rule

List prices read 2026-10-22, europe-west4 regional Standard: Class A $0.005 / 1,000, Class B
$0.0004 / 1,000, storage $0.020 / GiB-month, egress $0.12 / GiB, deletes free, 4xx/5xx generally not charged.

- Planned run: 487 × A + 410 × B ≈ $0.0026; egress ≈ 2 MiB ≈ $0.0003; storage ≈ 41 KiB × ≈ 2 h ≈ $0;
  ≈ **$0.003 per run**. At the caps (1,800 requests, all Class A, ≈ 150 MiB egress) ≈ $0.03 per run, two runs
  ≈ $0.06.
- Stop rule (Cloud Billing cannot cap spend and its data lags by hours): the harness caps bound each
  run. Before a second attempt and before cleanup, check the latest billing report at gross cost (credits
  excluded) and stop if it shows **≥ $1.00**, or any SKU other than Cloud Storage operations, storage or
  network egress, or after any accepted Locked mutation (exit 5). An optional $1 alerts-only budget needs
  Billing Account Costs Manager or Administrator.

### 7.9 Exact operation (after approval)

All steps go through `scripts/n02-gcs-acceptance-op.sh` from a clean worktree at the approved commit;
state lives in `$HOME/parkio-n02-483/state` (never in the repository). Each step stops at the first
failed check.

```bash
export CLOUDSDK_CONFIG="$HOME/.config/gcloud-parkio-n02-483" N02_GCLOUD="$HOME/google-cloud-sdk/bin/gcloud"
export N02_APPROVED_SHA=<approved merge SHA>     # the clean worktree is checked out at this commit
scripts/n02-gcs-acceptance-op.sh preflight      # read-only
N02_CONFIRM=provision-parkio-n02-accept-5c2c7f N02_BILLING_ACCOUNT=<approved id> \
  scripts/n02-gcs-acceptance-op.sh provision    # creates, verifies, records number + lien baseline
# owner, interactive, same CLOUDSDK_CONFIG:
#   "$N02_GCLOUD" auth application-default login --no-launch-browser
#   "$N02_GCLOUD" auth application-default set-quota-project parkio-n02-accept-5c2c7f
scripts/n02-gcs-acceptance-op.sh run            # >= 7 min after provision; again only after a diagnosed failure
scripts/n02-gcs-acceptance-op.sh forget-credentials   # right after the last run
```

Every command except `preflight` runs only from a checkout at `N02_APPROVED_SHA` and logs it. `provision`
refuses without the confirmation, with an unopened billing account, or when the project ID exists without
its own record; after a failure it resumes on the recorded project (same number, billing account and
operator) and creates only what is missing, waiting 60 s after new service accounts and retrying roles and
bindings. `run` refuses IAM bindings younger than 7 minutes, liens that differ from the recorded baseline,
ADC that is not a user login with this quota project, and a third counted attempt.

### 7.10 Cleanup, ownership and latest time

- **Earliest cleanup:** the latest `latestRetainUntil` over all attempts (an attempt without result:
  its harness bound from `run.json`, else its start + 2 h 45 min) + 5 min clock margin. **Latest expected
  cleanup:** the start of the last attempt + 2 h 50 min (an attempt that starts after about 21:10 local time
  closes its window the next day).
- **Owner of the cleanup:** the agent session that ran the operation, on the owner's go-ahead after
  the earliest cleanup time (no scheduled automation); if that session is gone, the owner runs the same
  commands from the same state directory.
- Commands, after a fresh `gcloud auth login --no-launch-browser` in the same directory:
  `scripts/n02-gcs-acceptance-op.sh cleanup-status`, then
  `N02_CONFIRM=cleanup-parkio-n02-accept-5c2c7f-<project number> scripts/n02-gcs-acceptance-op.sh cleanup`,
  then `scripts/n02-gcs-acceptance-op.sh forget-credentials`.
- `cleanup-status` is read-only. `cleanup` needs that confirmation and, before deleting anything, checks: project ID, recorded number and creation time, ACTIVE,
  labels; liens equal to the recorded baseline; only the two approved buckets (from a successful listing), each
  in this project and labelled, with no generation still retained. It then deletes all generations (only when
  some exist) and both buckets, requires the project's bucket listing to be empty, deletes the 4 service
  accounts and the 6 roles, then the lien only when it is the single lien identical to the baseline (parent
  `projects/<number>` or `projects/<id>`, restriction `resourcemanager.projects.delete`), and the project **by
  its number**. A refused or interrupted cleanup can be run again. It revokes nothing.
- Record in #483: verdict, `SHA256SUMS`, the empty listings, bucket and project deletion, lien status.

## 8. Findings for #365 (the live run confirms or refutes)

| # | Finding | Disposition |
|---|---------|-------------|
| 1 | Versioning was not checked at discovery; without it a rewrite of a retained name is refused and after expiry would replace history | Fixed in #491 (`requireObjectRetentionEnabled`) |
| 2 | A store write refused with 429 was never sent again; under the per-object limit the two back-to-back frontier writes made every checkpoint fail, and each later call consumed a new sequence and left three retained generations | #492, fixed in #491 (adapter resends); confirmed or refuted live by S5/S5r/S5s/T1. The resend waits up to 16.5 s per write while holding the store lock, on the request thread for records: bound the pause or fail fast to the retry worker before enablement (#365) |
| 3 | `publishCheckpoint` has no resume path for a published but uncovered checkpoint; every call writes the frontier twice | Open under #365 (design); harmless while #492's resend holds |
| 4 | A versioned overwrite needs `storage.objects.delete`, so the production writer holds delete; retention, not IAM, protects evidence | L2 measures, L3 + G5 prove; production role decision under #365 |
| 5 | Unbounded `listAll` pagination | #493, open; blocks enablement |
| 6 | Per-write list cost grows with recorded history | #494, open; blocks enablement |
| 7 | Versioning is only checked at startup; a later suspension makes rewrites fail closed (`PENDING_DURABLE`) | Open under #365 (periodic re-check or alert) |
