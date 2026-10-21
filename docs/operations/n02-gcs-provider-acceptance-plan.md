# N-02 — Real GCS provider acceptance (#483): plan, harness and execution proposal

**Status (2026-10-22):** harness ready; **nothing provisioned**. No project, bucket, IAM change, retention
lock or upload has been made. §7 is the exact operation awaiting owner approval.
**Parent:** #365 · **Adapter:** #482 / #484 · **Scope:** real-provider acceptance (layer C) only.
**Data:** synthetic only. **Production retention duration:** remains unset. **Bucket Lock:** out of scope.

## 1. Scope

| In scope | Out of scope |
|----------|--------------|
| The merged adapter and store against one dedicated, non-production Cloud Storage bucket with Object Retention Lock | Production export, enablement, exposure or legal retention (#365, layer D) |
| Synthetic evidence written by the harness; short test retention | Bucket Lock (bucket retention policy) on any bucket |
| Machine-readable, sanitized evidence tied to the source SHA and the bucket identity | Emulators, fakes and the offline model: they **do not** satisfy #483 |

## 2. What the run exercises

The harness (`GcsAcceptanceTool`, test classpath only) drives the merged classes, not equivalent `gcloud` calls:

- `GcsObjectRetentionBucket` over `GoogleCloudStorageTransport`, built the way `GcsDurableStoreConfig`
  builds it (JSON API over HTTP, default retry settings). Only the credentials differ: Application
  Default Credentials impersonate one service account per identity; no key file is used.
- `ObjectLockDurableErasureRecordStore` (putIfAbsent, findByRequestId, publishCheckpoint) on that bucket,
  confined to the run's namespace `runs/<run-id>/store/` by `PrefixedEvidenceBucket`.
- `ObjectLockEvidenceObjects`, `DurableErasureEvidenceVerifier.recover` and `EvidenceBundle`: the existing
  verifier, run locally over bytes the read-only identity read from the live bucket.
- Raw client calls only where the adapter has no operation: bucket metadata, shorten / clear / change
  retention, delete a generation.

Not exercised live: Spring wiring of `GcsDurableStoreConfig` (covered by `GcsDurableStoreConfigTest`),
several auth instances writing one bucket (outside the store's contract), Bucket Lock.

## 3. Provider facts that shape the run

Read from Google documentation on 2026-10-22; the run confirms or refutes each one.

| Fact | Consequence | Source |
|------|-------------|--------|
| Object Retention Lock is enabled at bucket creation (`--enable-per-object-retention`); existing buckets only in the console; it cannot be disabled; a best-effort lien is placed on `projects.delete` | Enable it on a new, dedicated bucket only; treat it and the lien as irreversible | [object-lock](https://docs.cloud.google.com/storage/docs/object-lock), [using-object-lock](https://docs.cloud.google.com/storage/docs/using-object-lock) |
| Locked: retain-until can only increase; mode cannot change. Unlocked: shorten / remove / lock needs `overrideUnlockedRetention=true` | K and G cases | same |
| A retained object cannot be deleted or replaced; with versioning a retained live object can still become noncurrent | Versioning is required (the store rewrites the frontier); P3 proves the noncurrent generation stays Locked | object-lock |
| `objects.insert` needs `storage.objects.create`; `storage.objects.setRetention` when the body carries retention; `storage.objects.delete` when it overwrites a name | The writer role needs `setRetention` (every adapter write sets retention) and, per the docs, `delete`; L2 measures the overwrite rule | [iam-json](https://docs.cloud.google.com/storage/docs/access-control/iam-json) |
| `objects.patch` needs `storage.objects.update`, `setRetention` for retention, `overrideUnlockedRetention` when overriding | Probe-admin role | iam-json |
| The Java client patches only the live object (no `generation` parameter on PATCH) | Probes patch the live generation under `ifGenerationMatch` (request-shape test) | client source 2.60.0 |
| About one write and one metadata update per second per object name; bucket create/delete about one per 2 s per project | History writes paced at 1.1 s; throttling of the store's back-to-back frontier writes is measured | [quotas](https://docs.cloud.google.com/storage/quotas) |
| A create without precondition is not retried by the client; gets and lists are | Logical operations are exact; HTTP requests can exceed them only by client retries | client source 2.60.0 |
| Requests answered 4xx/5xx are generally not charged; deletes are free | Negative cases cost nothing | [pricing](https://cloud.google.com/storage/pricing) |

## 4. Corrections to the previous version of this plan

| Previous | Corrected | Why |
|----------|-----------|-----|
| ~20–40 API calls | **773 logical operations** (413 Class A, 330 Class B, 29 refused, 1 free delete), from the offline plan of the full sequence | 205 generations are needed for ≥ 3 pages at the adapter's fixed page size 100; `allVersions` reads every generation; the store lists `sequences/` and every frontier generation on each put; concurrency, retries, negative cases, verification and export |
| IAM: create / get / list + "retention get/set" | Four custom roles (§7.4) | Retention on insert needs `setRetention`; overwrite needs `delete`; PATCH needs `update` (+ override) |
| COMPLIANCE retain-until 24 h | **2 h** (§7.6) | No case needs an overnight re-check; the cleanup after expiry is the re-check |
| Versioning assumed | Required, checked by the preflight **and** now by the adapter's discovery | Object Retention Lock does not imply versioning (unlike S3 Object Lock) |
| "SA without list", unavailable store, incomplete coverage as live cases | Local tests (in-memory transport, verifier fixtures); live V cases verify complete live evidence and S4 checks the interim state | Those paths are provider-independent |
| Cleanup "remove test SA keys" | No keys exist; cleanup deletes objects, buckets, service accounts, roles and (dedicated project) the lien | Keyless impersonation |

## 5. Cases

Live evidence unless marked. Every write and read uses the adapter; probes are raw client calls.

| Id | Identity | Passes when |
|----|----------|-------------|
| F5 | reader | Preflight: both buckets in the approved location, STANDARD, UBLA, PAP enforced, versioning on, soft delete off, no retention policy, no HNS, labels; ORL on the test bucket only. **Nothing is written unless F5 passes** |
| F1, F2 | reader, writer | `requireRetentionFeature` passes |
| F3 | reader | Refused on the control bucket, because the feature is missing (not because of IAM) |
| F4 | reader | Refused (fail-closed) on an absent bucket, provider 404/403 |
| F6 | writer-nodelete, probe-admin | Both identities authenticate and read the bucket; F1, F2 and F6 must pass before any write |
| P1, P2 | writer | GOVERNANCE / COMPLIANCE put; reported mode and retain-until equal the request; bytes by generation |
| P3 | writer | Overwrite of a Locked live object adds a higher generation; the first stays canonical and Locked |
| H1 | writer | 205 sequential writes (1.1 s apart) get strictly increasing generations |
| H2 | reader | `versionCount`, `oldest`, `allVersions` see all 205 across ≥ 3 pages each; every generation holds its own write |
| H3 | reader | Transport paging at 32 per page: same set, no duplicates, ≥ 7 pages; prefix key listing complete |
| C1 | writer | 8 concurrent writers: canonical is the lowest listed generation with its writer's bytes; every listed generation is accounted for (throttled writers recorded) |
| C2 | writer | An identical body twice gives two generations; the first stays canonical |
| S1–S3 | writer | Store put: COMPLIANCE receipt on the canonical generation; identical retry settles without a write; different body is a conflict without a write |
| S4 | writer | Response lost **after** the provider committed the record: not durable before the frontier covers it; the retry settles on that generation without a second write |
| S5 | writer | Measurement: checkpoint publication as merged, unpaced (see §8.2) |
| S5b | writer | Checkpoint completes when writes to one name are spaced 1.1 s (only if S5 did not converge) |
| S6 | writer | Further records converge under the per-object limit; frontier history recorded |
| V1 | reader | Local verifier over the live store: ACCEPT_ISOLATED, no gaps, no abandoned reservations, boundary = highest sequence (local verifier, live bytes) |
| V2 | reader | Exported bundle verifies with the identical verdict; bundle and SHA-256 kept (local verifier, live bytes) |
| L1 | reader | Write refused 403; nothing created |
| L2 | writer-nodelete | Measurement: does a versioned overwrite need `storage.objects.delete`? |
| L3, L4 | writer | Cannot delete a retained GOVERNANCE generation, cannot shorten it even with override |
| K1–K5 | probe-admin | Locked: shorten, remove, switch to Unlocked, delete live, delete noncurrent — all refused, state unchanged |
| K6 | probe-admin | Positive control: Locked retain-until extended by 60 s |
| K7 | probe-admin | A record the store published under COMPLIANCE cannot be deleted |
| G1–G4 | probe-admin | GOVERNANCE: delete refused; shorten without override refused; with override shortened, removed, then deleted |

A refusal counts only with a 4xx other than 401/404/412/429 **and** an unchanged generation; anything
else is an ERROR, never a pass.

## 6. Harness

```bash
# offline plan of the full sequence (no network, not evidence)
./gradlew --no-daemon --max-workers=1 :services:auth-service:gcsProviderAcceptance --args="plan <empty dir>"
# live run, owner-approved resources only (§7.9)
./gradlew --no-daemon --max-workers=1 :services:auth-service:gcsProviderAcceptance --args="run <empty dir>"
```

- Settings: `N02_GCS_PROJECT`, `N02_GCS_LOCATION`, `N02_GCS_BUCKET`, `N02_GCS_CONTROL_BUCKET`,
  `N02_GCS_WRITER_SA`, `N02_GCS_WRITER_NODELETE_SA`, `N02_GCS_READER_SA`, `N02_GCS_PROBE_ADMIN_SA`,
  `N02_GCS_CONFIRM` (must repeat the bucket name). No secrets.
- Refusals before any provider call: names outside `parkio-n02-orl-accept-<yyyymmdd>-<hex6>` /
  `parkio-n02-noorl-ctrl-<same>`, accounts outside the project, a shared account, a modified checkout,
  an unknown SHA, a non-empty output directory, a service-account key file as ADC.
- Stop thresholds, checked before every storage operation (client retries inside one operation are
  counted, not gated): 1,200 logical operations, 1,500 HTTP requests, 320 generations, 40 COMPLIANCE
  generations, 45 min. The plan uses 773 / ≈773 / 236 / 25 / ~4.3 min of waits.
- Output: `result.json` (verdict, source SHA/tree, client versions, bucket snapshot incl. project number
  and creation time, identities, per-case evidence and operations, budget, list-price cost, cleanup
  inventory with every generation and its retain-until), synthetic `bundle.json`, `SHA256SUMS`. Provider
  messages are sanitized; the synthetic HMAC key exists only in memory.
- `evidenceClass` is `live-provider` only for `run`; `countsFor483` is true only for a live PASS.
- Local tests (not evidence): `GcsAcceptanceToolTest` (offline sequence, one test per modelled provider
  defect, caps, secret scan, refusals) and `LiveGcsAcceptanceProviderRequestShapeTest` (what the real
  client sends, recorded by a local HTTP endpoint). Eleven mutants of the harness gates and of the
  adapter's versioning check were all killed by these tests.

## 7. Execution proposal (awaiting owner approval)

### 7.1 Project

Owner selection is **missing**: this workstation has no Google Cloud CLI or ADC, and the repository
records no Parkio Google Cloud project or migration sandbox. Recommended: a **new dedicated project**
`parkio-n02-accept-5c2c7f` with the owner's billing account linked, used for nothing else. Reason: the
Object Retention Lock lien and all IAM changes stay inside it, and the whole project can be deleted after
cleanup. An existing non-production project works only if the owner accepts the lien there.

### 7.2 Region

`europe-west4` (Netherlands): the same jurisdiction as the approved offsite backup (Azure `westeurope`),
so the run tests where a production evidence bucket would plausibly live; lowest EU Standard price
($0.020/GiB-month; Frankfurt is $0.023); single region keeps regional operation rates and avoids
replication charges. US Always Free does not matter at this cost.

### 7.3 Buckets and features

| | Test bucket | Control bucket |
|---|---|---|
| Name | `parkio-n02-orl-accept-20261022-5c2c7f` | `parkio-n02-noorl-ctrl-20261022-5c2c7f` |
| Location / class | europe-west4, STANDARD | same |
| Object Retention Lock | **enabled (irreversible)** | off |
| Versioning | on | on |
| Soft delete | off (`--clear-soft-delete`) | off |
| UBLA / public access prevention | on / enforced | on / enforced |
| Retention policy (Bucket Lock), lifecycle, Autoclass, HNS, CMEK | none | none |
| Labels | `parkio-purpose=n02-gcs-acceptance`, `parkio-data=synthetic`, `parkio-issue=483` | same |

### 7.4 IAM changes (all inside the dedicated project)

- APIs: `storage`, `iam`, `iamcredentials`, `cloudresourcemanager`.
- Service accounts, **no keys**: `n02-writer`, `n02-writer-nodelete`, `n02-reader`, `n02-probe-admin`.
- Custom roles (definitions grant nothing by themselves):

| Role | Permissions |
|------|-------------|
| `n02EvidenceWriter` | storage.buckets.get, storage.objects.create, storage.objects.delete, storage.objects.get, storage.objects.list, storage.objects.setRetention |
| `n02EvidenceWriterNoDelete` | the writer role without storage.objects.delete |
| `n02EvidenceReader` | storage.buckets.get, storage.objects.get, storage.objects.list |
| `n02RetentionProbeAdmin` | storage.buckets.get, storage.objects.get, storage.objects.update, storage.objects.delete, storage.objects.setRetention, storage.objects.overrideUnlockedRetention |

- Bindings on the **test bucket** only: each account to its role. On the **control bucket**: `n02-reader` to `n02EvidenceReader`.
- On each service account: the owner's user to `roles/iam.serviceAccountTokenCreator` (impersonation).
- Nothing at project or organization level besides the project's existing owner.

### 7.5 Synthetic data and limits

Harness-generated JSON only (`parkio-n02-acceptance-synthetic`, random UUIDs, synthetic database identity,
HMAC key generated in memory per run). Never production evidence, trust documents or credentials.
Per run: ≤ 320 generations (plan 236: 211 GOVERNANCE, 25 COMPLIANCE), ≤ 40 COMPLIANCE, ≈ 38 KiB.
At most **two runs** under this approval (the second only after a diagnosed failure).

### 7.6 Retention and irreversible consequences

- GOVERNANCE (Unlocked): run start + **2 h** (211 generations).
- COMPLIANCE (Locked): write time + **2 h** (25 generations; K6 adds 60 s to one). The latest
  retain-until is in `result.json` (`latestRetainUntil`, ≈ run start + 2 h 10 min).
- Why 2 h: the run is capped at 45 min and the Locked refusals run last, so every Locked object stays
  retained through the last probe with more than an hour of margin, while the irreversible window ends
  the same day.
- Irreversible: Object Retention Lock can never be disabled on the test bucket; the project carries a
  lien until removed; nobody (project owner included) can shorten, remove or delete a Locked generation
  before its retain-until; neither bucket nor (dedicated) project can be deleted before all generations
  are gone; those objects are billed until deleted.

### 7.7 Cost and stop threshold

List prices read 2026-10-22, europe-west4 regional Standard: Class A $0.005 / 1,000, Class B
$0.0004 / 1,000, storage $0.020 / GiB-month, deletes free, 4xx/5xx generally not charged.

- Planned run: 413 × A + 330 × B ≈ **$0.0022**; storage ≈ 38 KiB × ~2 h ≈ $0.000000002; egress to the
  workstation (< 100 KiB) < $0.00002. At the request cap (1,500, all Class A): ≈ $0.0075. Two runs < $0.02.
- Stop threshold (billing cannot enforce a hard cap): the harness aborts at its caps (§6); the owner
  stops and does not rerun if the project's billing shows **≥ $1.00** or any SKU other than Cloud
  Storage operations/storage, after two runs, or if any K case reports an accepted Locked mutation
  (provider contradicts the docs → contingency route C). Optional: a $1 budget with 50/90/100 % alerts
  (alerts only).

### 7.8 Prerequisites on the operator workstation (WSL)

```bash
# Google Cloud CLI without sudo (user install)
curl -fsSLO https://dl.google.com/dl/cloudsdk/channels/rapid/downloads/google-cloud-cli-linux-x86_64.tar.gz
tar -xf google-cloud-cli-linux-x86_64.tar.gz && ./google-cloud-sdk/install.sh --quiet --path-update=true
exec bash -l
gcloud config configurations create parkio-n02-accept   # separate configuration
gcloud auth login --no-launch-browser
```

### 7.9 Exact operation (after approval)

```bash
P=parkio-n02-accept-5c2c7f; R=europe-west4
B=parkio-n02-orl-accept-20261022-5c2c7f; C=parkio-n02-noorl-ctrl-20261022-5c2c7f
ME=$(gcloud config get-value account)

gcloud projects create "$P" --name="parkio-n02-accept"
gcloud billing projects link "$P" --billing-account=<BILLING_ACCOUNT_ID>
gcloud config set project "$P"
gcloud services enable storage.googleapis.com iam.googleapis.com iamcredentials.googleapis.com cloudresourcemanager.googleapis.com

for b in "$B" "$C"; do
  extra=$([ "$b" = "$B" ] && echo --enable-per-object-retention)
  gcloud storage buckets create "gs://$b" --location="$R" --default-storage-class=STANDARD \
    --uniform-bucket-level-access --public-access-prevention $extra
  gcloud storage buckets update "gs://$b" --versioning --clear-soft-delete \
    --update-labels=parkio-purpose=n02-gcs-acceptance,parkio-data=synthetic,parkio-issue=483
  sleep 3
done

for a in n02-writer n02-writer-nodelete n02-reader n02-probe-admin; do
  gcloud iam service-accounts create "$a" --display-name="N-02 #483 $a (no keys)"
done
gcloud iam roles create n02EvidenceWriter --project="$P" --stage=GA --permissions=storage.buckets.get,storage.objects.create,storage.objects.delete,storage.objects.get,storage.objects.list,storage.objects.setRetention
gcloud iam roles create n02EvidenceWriterNoDelete --project="$P" --stage=GA --permissions=storage.buckets.get,storage.objects.create,storage.objects.get,storage.objects.list,storage.objects.setRetention
gcloud iam roles create n02EvidenceReader --project="$P" --stage=GA --permissions=storage.buckets.get,storage.objects.get,storage.objects.list
gcloud iam roles create n02RetentionProbeAdmin --project="$P" --stage=GA --permissions=storage.buckets.get,storage.objects.get,storage.objects.update,storage.objects.delete,storage.objects.setRetention,storage.objects.overrideUnlockedRetention

bind() { gcloud storage buckets add-iam-policy-binding "gs://$1" --member="serviceAccount:$2@$P.iam.gserviceaccount.com" --role="projects/$P/roles/$3"; }
bind "$B" n02-writer n02EvidenceWriter
bind "$B" n02-writer-nodelete n02EvidenceWriterNoDelete
bind "$B" n02-reader n02EvidenceReader
bind "$B" n02-probe-admin n02RetentionProbeAdmin
bind "$C" n02-reader n02EvidenceReader
for a in n02-writer n02-writer-nodelete n02-reader n02-probe-admin; do
  gcloud iam service-accounts add-iam-policy-binding "$a@$P.iam.gserviceaccount.com" --member="user:$ME" --role=roles/iam.serviceAccountTokenCreator
done

gcloud auth application-default login --no-launch-browser
gcloud auth application-default set-quota-project "$P"

# from a clean checkout of the reviewed harness commit
export N02_GCS_PROJECT="$P" N02_GCS_LOCATION="$R" N02_GCS_BUCKET="$B" N02_GCS_CONTROL_BUCKET="$C" N02_GCS_CONFIRM="$B"
export N02_GCS_WRITER_SA="n02-writer@$P.iam.gserviceaccount.com" N02_GCS_WRITER_NODELETE_SA="n02-writer-nodelete@$P.iam.gserviceaccount.com"
export N02_GCS_READER_SA="n02-reader@$P.iam.gserviceaccount.com" N02_GCS_PROBE_ADMIN_SA="n02-probe-admin@$P.iam.gserviceaccount.com"
./gradlew --no-daemon --max-workers=1 :services:auth-service:gcsProviderAcceptance --args="plan $HOME/parkio-n02-483/plan-1"
./gradlew --no-daemon --max-workers=1 :services:auth-service:gcsProviderAcceptance --args="run $HOME/parkio-n02-483/run-1"
```

IAM changes can take a minute to propagate; if F5/F6 fail with 403 right after the bindings, wait and
rerun into a new directory (nothing was written).

### 7.10 Cleanup and follow-up

Right after the run: `gcloud storage buckets delete "gs://$C"` (control bucket holds nothing).
After `latestRetainUntil` from `result.json` (≈ run start + 2 h 10 min). If a run was interrupted and
wrote no result, use the bound run start + 45 min (cap) + 2 h + 60 s, i.e. **run start + 2 h 50 min**:

```bash
gcloud storage rm --all-versions "gs://$B/**"   # refused for anything still retained
gcloud storage ls --all-versions "gs://$B/**"   # must print nothing
gcloud storage buckets delete "gs://$B"
for a in n02-writer n02-writer-nodelete n02-reader n02-probe-admin; do gcloud iam service-accounts delete "$a@$P.iam.gserviceaccount.com" --quiet; done
for r in n02EvidenceWriter n02EvidenceWriterNoDelete n02EvidenceReader n02RetentionProbeAdmin; do gcloud iam roles delete "$r" --project="$P" --quiet; done
gcloud components install alpha --quiet
gcloud alpha resource-manager liens list --project="$P"     # dedicated project: delete the ORL lien, then the project
gcloud alpha resource-manager liens delete <LIEN_ID>
gcloud projects delete "$P" --quiet
gcloud auth application-default revoke --quiet
```

Record in #483: the result verdict and `SHA256SUMS`, the empty listing, bucket and project deletion,
and lien status. No automation schedules this cleanup; it is a manual step after the retain-until.

## 8. Findings for #365 (from the code review and the offline plan; the live run confirms or refutes)

1. **Versioning was not checked at discovery.** Object Retention Lock does not imply versioning; without
   it a rewrite of a retained name is refused (the store sticks at `PENDING_DURABLE`) and, after expiry,
   would replace history. Fixed with this harness: `GoogleCloudStorageTransport.requireObjectRetentionEnabled`
   also requires versioning.
2. **Per-object write limit vs back-to-back frontier writes.** `putIfAbsent` writes the frontier twice per
   new record; a throttled second write is completed by the retry. `publishCheckpoint` restarts with a
   new sequence on retry, so under strict one-write-per-second throttling it never completes unpaced
   (S5 measures it; S5b completes it with 1.1 s spacing). If the live run confirms throttling, production
   needs pacing or a frontier design that avoids rewriting one name in a burst.
3. **Overwrite needs `storage.objects.delete`** per the docs: the production writer would hold delete;
   retention, not IAM, protects evidence (L2, L3).
4. **Cost and latency grow with history:** every put lists all frontier generations and reads each
   sequence marker; ≥ 100 frontier generations already mean several list pages per put.
5. **`listAll` pagination is unbounded** (no page cap or repeated-token guard); bound it before enablement.
