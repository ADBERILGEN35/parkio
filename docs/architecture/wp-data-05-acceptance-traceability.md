# DATA-WP-05 acceptance traceability

Local implementation baseline: `deb557d07dfb64e8ddad2b6697cfbc2e5c58605f`.
Acceptance-closure follow-up adds the cited tests and this matrix.
`PASS` means a concrete automated assertion exists in the cited method.
Hosted-beta live execution remains DATA-WP-05A and is not claimed here.

| # | Requirement | Test class and method | Layer | DB-backed | Result | Assertion notes |
|---:|---|---|---|:---:|:---:|---|
| 1 | Supported İZUM↔OSM pair | `RegistrySourceFamilyPairTest.resolvesEnabledPairCaseInsensitivelyAndCanonicalizesKey` | Unit | N | PASS | Resolves to canonical `IZUM_OSM` |
| 2 | Unsupported pair rejection | `RegistrySourceFamilyPairTest.rejectsDisabledAndSameFamilyPairs`; `RegistryLinkCandidateGenerationControllerTest.unsupportedSourcePairReturns400`; `MunicipalRegistryCandidateGenerationPostgresIT.independentPairLocksDoNotBlockWhenOnlyIzumOsmSupported` | Unit + HTTP + IT | Y | PASS | Disabled IZUM/IZELMAN rejected with IllegalArgumentException / HTTP 400 |
| 3 | Default bounds | `LinkCandidateGenerationBoundsTest.appliesDefaultsAndClampsMaxima` | Unit | N | PASS | Null inputs become 100m / 100 left / 1000 pairs / 20 samples |
| 4 | Maximum bounds | `LinkCandidateGenerationBoundsTest.appliesDefaultsAndClampsMaxima` | Unit | N | PASS | Oversized inputs clamp to 250m / 1000 left / 10000 pairs / 20 samples |
| 5 | Invalid bounds | `LinkCandidateGenerationBoundsTest.rejectsNonPositiveValues`; `RegistryLinkCandidateGenerationControllerTest.invalidBoundsReturns400` | Unit + HTTP | N | PASS | Non-positive values throw; controller maps to 400 |
| 6 | Spatial pair limit | `MunicipalRegistryCandidateGenerationPostgresIT.pairLimitAndDeterministicOrderingAreHonored` | Integration | Y | PASS | `pairsConsidered == 2` with `pairLimit=2` against 3 OSM neighbors |
| 7 | Deterministic ordering | `MunicipalRegistryCandidateGenerationPostgresIT.pairLimitAndDeterministicOrderingAreHonored` | Integration | Y | PASS | Two runs return identical sample external-id order |
| 8 | Dry-run candidate non-mutation | `MunicipalRegistryCandidateGenerationPostgresIT.dryRunWritesOnlyGenerationRunAuditAndNoRegistryMutations`; `LinkCandidateGenerationOrchestratorTest.dryRunEvaluatesWithoutCandidateWritesAndCompletesAudit` | Integration + Unit | Y | PASS | Zero `municipal_link_candidates` growth; generation-run audit may increase with `dry_run=true` |
| 9 | Persist candidate insertion | `MunicipalRegistryCandidateGenerationPostgresIT.persistModeInsertsPendingOnlyAndIsIdempotent` | Integration | Y | PASS | Exactly one `PENDING` candidate; no ACCEPTED/review audit |
| 10 | Persist rerun idempotency | `MunicipalRegistryCandidateGenerationPostgresIT.persistModeInsertsPendingOnlyAndIsIdempotent` | Integration | Y | PASS | Second run `duplicatesSuppressed >= 1`; candidate count remains 1 |
| 11 | Same-version decision suppression | `MunicipalRegistryCandidateGenerationPostgresIT.rejectedSameVersionIsSuppressedOnRerun` | Integration | Y | PASS | REJECTED same versions remain one row after persist regenerate |
| 12 | Source-version regeneration | `MunicipalRegistryCandidateGenerationPostgresIT.changedSourceVersionAllowsNewCandidate` | Integration | Y | PASS | Hash/version change permits additional candidate row |
| 13 | Already-linked suppression | `MunicipalRegistryCandidateGenerationPostgresIT.alreadyLinkedIsSkipped`; `LinkCandidateGenerationOrchestratorTest.alreadyLinkedSkipsWithoutCallingGeneration` | Integration + Unit | Y | PASS | `already_linked` skip; generator not invoked |
| 14 | Distance-only skip | `RegistryPolicyTest.distanceAloneAndNameAloneNeverGenerateCandidates`; `MunicipalRegistryCandidateGenerationPostgresIT.distanceOnlyAndNameOnlyDoNotPersistCandidates` | Unit + Integration | Y | PASS | Policy reason `distance_only`; no candidate persist |
| 15 | Name-only skip | `RegistryPolicyTest.distanceAloneAndNameAloneNeverGenerateCandidates`; `MunicipalRegistryCandidateGenerationPostgresIT.distanceOnlyAndNameOnlyDoNotPersistCandidates` | Unit + Integration | Y | PASS | Policy reason `name_only`; no candidate persist |
| 16 | Multi-signal candidate | `RegistryPolicyTest.multiSignalEvidenceGeneratesReviewCandidateButNeverAutoLinks` | Unit | N | PASS | `candidate=true`, `mayAutoLink=false` |
| 17 | Hard-conflict classification | `RegistryPolicyTest.hardConflictsSurfaceForReviewAndBlockCandidateLinking`; `MunicipalRegistryCandidateGenerationPostgresIT.hardConflictIsClassifiedWithoutApplying` | Unit + Integration | Y | PASS | Hard conflicts counted; no ACCEPTED/alias mutation |
| 18 | Concurrent run conflict | `RegistryLinkCandidateGenerationControllerTest.concurrentRunReturns409`; `MunicipalRegistryCandidateGenerationPostgresIT.concurrentPairConflictsAndDisabledIzelmanPairIsRejected` | HTTP + Integration | Y | PASS | HTTP 409 / ConcurrentGenerationException while RUNNING |
| 19 | Lock release after failure | `MunicipalRegistryCandidateGenerationPostgresIT.lockReleasedAfterFailedRunAllowsRetry` | Integration | Y | PASS | After FAILED completion, `tryStart` succeeds |
| 20 | Partial failure audit state | `LinkCandidateGenerationOrchestratorTest.processingFailureYieldsPartialStatusWhenOnePairThrows` | Unit | N | PASS | Status `PARTIAL` with failure/skip aggregates |
| 21 | No source-link movement | `MunicipalRegistryCandidateGenerationPostgresIT.dryRunWritesOnlyGenerationRunAuditAndNoRegistryMutations`; `...persistModeInsertsPendingOnlyAndIsIdempotent` | Integration | Y | PASS | `municipal_facility_source_links` count unchanged |
| 22 | No alias/supersede mutation | same IT methods as #21 + hard-conflict IT | Integration | Y | PASS | `municipal_facility_aliases` count unchanged |
| 23 | No occupancy mutation | same IT methods as #21 | Integration | Y | PASS | `municipal_occupancy_snapshots` count unchanged |
| 24 | No tariff-assignment mutation | same IT methods as #21 | Integration | Y | PASS | `municipal_tariff_assignments` count unchanged |
| 25 | No public API candidate exposure | `RegistryCandidatePublicApiRegressionTest.municipalFacilityResponseDoesNotExposeCandidateOrGenerationRunFields` | Contract unit | N | PASS | Public DTO components forbid candidate/run names |
| 26 | Feature-off route absence | `RegistryPropertiesBindingTest.generationControllerBeanIsAbsentWhenCandidateGenerationDisabled` | Spring binding | N | PASS | Controller bean absent when flag false → 404 at edge |
| 27 | Automatic-linking prohibition | `RegistryPropertiesBindingTest.automaticLinkingTrueFailsContextStartup`; `RegistryPropertiesTest.automaticLinkingCannotBeEnabled` | Spring binding + Unit | N | PASS | Binding `true` fails startup; getter always false |
| 28 | Review mutation remains separately gated | `RegistryLinkReviewControllerTest.disabledReviewedLinkingIsConflict` | HTTP | N | PASS | Accept/reject path conflicts when `reviewed-linking` disabled |

## HTTP status matrix (closure)

| Case | Status | Test method |
|---|---:|---|
| unauthenticated generate | 401 | `unauthenticatedGenerateReturns401` |
| USER generate | 403 | `userGenerateReturns403` |
| feature-disabled generate route | 404 | `generationControllerBeanIsAbsentWhenCandidateGenerationDisabled` |
| ADMIN valid dry-run | 200 | `adminDryRunReturns200` |
| ADMIN valid persist | 200 | `adminPersistReturns200` |
| invalid bounds | 400 | `invalidBoundsReturns400` |
| contradictory dryRun/persist | 400 | `contradictoryDryRunPersistReturns400` |
| unsupported source pair | 400 | `unsupportedSourcePairReturns400` |
| concurrent same-pair run | 409 | `concurrentRunReturns409` |
| unknown run ID | 404 | `unknownRunReturns404` |
| malformed body | 400 | `malformedBodyReturns400` |
| unsupported HTTP method | 405 | `unsupportedMethodReturns405` |
| unexpected exception | 500 | `unexpectedExceptionReturns500WithoutLeak` |

Error bodies use `ApiError` (`code`, `message`, optional `traceId`). Leak tests assert absence of stack frames, SQL, JWT/token text, and internal class names.

## Dry-run audit semantics

`dryRun=true` creates zero `municipal_link_candidates` and zero mutation of links, aliases, supersede, occupancy, tariffs, and review audit. A generation-run audit row may be written with `dry_run=true`, `persist_candidates=false`, aggregate counts, and bounded samples only.

DATA-WP-05A remains pending.