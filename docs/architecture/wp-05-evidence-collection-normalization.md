# WP-05.3 — Repository-Grounded Evidence Collection and Normalization

**Status:** Complete (2026-07-27)  
**Related:** [WP-05.1 audit](./wp-05-parking-validation-current-state.md), [ADR](./adr/ADR-WP05-decision-engine-placement.md), [Domain model](./wp-05-decision-domain-model.md), [Implementation plan](./wp-05-implementation-plan.md)

---

## 1. Purpose

Introduce a deterministic, framework-free evidence acquisition and normalization layer inside `parking-service` that maps **existing** AI validation and parking-spot signals into canonical `EvidenceItem` / `EvidenceVector` values. Publication authority remains `ParkingApplicationService.applyAiValidationResult`.

---

## 2. Scope and non-goals

### In scope
- Pure normalizers for AI payload fields and optional `ParkingSpotEvidenceContext`
- `EvidenceVectorFactory` / `EvidenceCollectionService` assembly
- Fail-safe shadow observation in `AiValidationEventsKafkaConsumer` (debug logging only)
- Port cleanup: removed duplicate `AiEvidencePort`

### Out of scope (deferred)
- `EvidenceScore`, `RiskScore`, `DecisionResult`, `PublicationDisposition` selection
- Routing consumer through `DecisionPort`
- Trust / device / H3 / duplicate-detection evidence
- DB persistence of vectors, migrations, API/event schema changes
- Gamification trust fetch

---

## 3. Current evidence sources

| Signal | Category | Source symbol |
|--------|----------|---------------|
| `eventId`, `mediaId`, `parkingSpotId`, `status`, `detectedRiskTypes` | A | Kafka `AiValidationCompleted` payload consumed by `AiValidationEventsKafkaConsumer` |
| `emptySpaceConfidence`, `legalRiskScore`, `imageQualityScore`, `aiConfidence` | A | Same payload (contract v1 in `docs/architecture/event-contracts.md`; parsed at consumer, not passed to publication gate) |
| `occurredAt` | A | Envelope `EventEnvelope.occurredAt()` |
| `latitude`, `longitude`, `legalStatus`, `manualLocationEdited`, `mediaId` | B | `ParkingSpot` / `ParkingSpotEvidenceContext` (tests and future in-process callers) |
| `moderationDecidedAt` staleness | B | `ParkingSpot.isStaleModerationEvent` semantics via context |
| Trust score | C/D | Not collected |
| GPS accuracy, device integrity, H3, provenance hash | D | Not in repository |

---

## 4. Implemented evidence mappings

See section 17 for the full mapping table.

---

## 5. Missing / deferred evidence sources

- **User trust** (`TrustSignalPort`): requires gamification dependency — not fetched; no placeholder evidence.
- **Device integrity**: capability absent — no evidence emitted.
- **Duplicate / fraud / behavior rate**: no repository signal in this WP.
- **AI model/version/provenance hash**: not present on `AiValidationCompleted` payload v1.
- **Location evidence at runtime shadow**: consumer shadow path does not load `ParkingSpot` (no extra DB read); location normalizer used when context is supplied explicitly.

---

## 6. Normalization semantics

- Deterministic for identical inputs.
- Missing optional AI score fields → item omitted (not negative evidence).
- Malformed scores (outside 0–100) → `EvidenceNormalizationException`.
- Unsupported payload schema version (>1) → exception at input construction.
- Unknown `status` string → `AI_STATUS_UNKNOWN` neutral item.
- Unknown risk type strings → `AI_RISK_UNKNOWN` neutral item.
- AI `status` is normalized to evidence; it is **not** a `PublicationDisposition`.

---

## 7. Polarity semantics

| Polarity | Usage in WP-05.3 |
|----------|------------------|
| `SUPPORTS_PUBLISH` | PASSED status, positive scores, valid coordinates, legal OK |
| `OPPOSES_PUBLISH` | FAILED status, legal risk score, legal/placement risks, invalid coords, media/spot mismatch |
| `NEUTRAL` | WARNING status, AI confidence, low image quality risk, manual location edit, stale event, unknown enums |
| `ABSENT` | Not used (capability gaps do not emit ABSENT) |

---

## 8. Strength / confidence semantics

- Strength is 0–100 observation weight on each `EvidenceItem` (not `EvidenceScore`).
- AI numeric fields map 1:1 when present (`emptySpaceConfidence`, `imageQualityScore`, `aiConfidence` as supports/neutral; `legalRiskScore` as opposes with strength = score).
- Fixed strengths for status/risk tokens (documented in normalizer source).

---

## 9. Provenance and correlation handling

- `evaluationId` == AI `eventId`.
- `sourceReference` on AI-derived items = `eventId` string.
- `collectedAt` = AI `occurredAt` (never wall-clock in normalizers).
- Envelope `traceId` is not copied into evidence (avoid PII/log leakage in vectors).

---

## 10. EvidenceVector assembly

`EvidenceCollectionService.collect(EvidenceCollectionRequest)`:
1. AI normalizer
2. Operational normalizer (optional spot context)
3. Location normalizer when context present
4. `EvidenceVectorFactory.assemble` with dedupe by `EvidenceItem.equals`

`EvidenceVector` applies canonical sort via `EvidenceItem.canonicalKey()`.

---

## 11. Idempotency behavior

- Inbox dedupe (`inbox_events`) unchanged for publication path.
- Re-running normalization with the same request produces an equal `EvidenceVector`.
- Identical `EvidenceItem` instances deduped on assembly; semantically distinct items preserved.

---

## 12. Failure behavior

- Normalization exceptions are **not** mapped to REJECT/HOLD/SHADOW.
- Shadow path catches all runtime failures; never affects transaction or `applyAiValidationResult`.
- Identity mismatch on `EvidenceCollectionRequest` construction throws before assembly.

---

## 13. Runtime integration status

| Integration | Status |
|-------------|--------|
| `ParkingApplicationService.applyAiValidationResult` | **Unchanged** — authoritative gate |
| `AiValidationEventsKafkaConsumer.onMessage` | Unchanged dispatch; extended payload parsing for optional score fields |
| Shadow `observeEvidenceShadow` | **Enabled** — after successful inbox insert + apply; debug log only; no DB/Kafka/network |
| `EvidenceCollectionPort` Spring bean | **Not registered** — plain `EvidenceCollectionService` instantiated in shadow helper |

---

## 14. Security / privacy considerations

- Shadow logs spot id, evaluation id, item count only at DEBUG.
- No image bytes, coordinates, or full payloads logged.
- Normalized items exclude raw Kafka JSON.

---

## 15. Extension rules for future providers

1. Add a dedicated normalizer returning `List<EvidenceItem>`.
2. Extend `EvidenceCollectionService` orchestration — do not embed provider DTOs in `EvidenceItem`.
3. Prefer new `EvidenceProvider` implementations over provider-specific ports.
4. Category C signals require an ADR note before fetch in collection path.

---

## 16. File and symbol inventory

| Symbol | Path |
|--------|------|
| `AiValidationEvidenceInput` | `services/parking-service/.../decision/normalization/AiValidationEvidenceInput.java` |
| `ParkingSpotEvidenceContext` | `.../normalization/ParkingSpotEvidenceContext.java` |
| `EvidenceCollectionRequest` | `.../normalization/EvidenceCollectionRequest.java` |
| `AiValidationEvidenceNormalizer` | `.../normalization/AiValidationEvidenceNormalizer.java` |
| `ParkingSpotLocationEvidenceNormalizer` | `.../normalization/ParkingSpotLocationEvidenceNormalizer.java` |
| `OperationalEvidenceNormalizer` | `.../normalization/OperationalEvidenceNormalizer.java` |
| `EvidenceVectorFactory` | `.../decision/application/EvidenceVectorFactory.java` |
| `EvidenceCollectionService` | `.../decision/application/EvidenceCollectionService.java` |
| `EvidenceCollectionPort` | `.../decision/port/EvidenceCollectionPort.java` |
| `AiValidationEvidencePayloadMapper` | `.../infrastructure/messaging/AiValidationEvidencePayloadMapper.java` |
| `AiValidationEventsKafkaConsumer.onMessage` | `.../infrastructure/messaging/AiValidationEventsKafkaConsumer.java` |
| `ParkingApplicationService.applyAiValidationResult` | `.../application/ParkingApplicationService.java` |
| `AiValidationCompletedEvent` (producer) | `services/ai-validation-service/.../domain/event/AiValidationCompletedEvent.java` |

**Removed:** `AiEvidencePort` (duplicate of `EvidenceProvider` / collection pipeline).

---

## 17. Source-field → canonical evidence mapping

| Source field | EvidenceType | EvidenceSource | ReasonCode | Polarity | Strength |
|--------------|--------------|----------------|------------|----------|----------|
| `status=PASSED` | AI_CONTENT_ANALYSIS | AI_VALIDATION_SERVICE | AI_STATUS_PASSED | SUPPORTS_PUBLISH | 55 |
| `status=WARNING` | AI_CONTENT_ANALYSIS | AI_VALIDATION_SERVICE | AI_STATUS_WARNING | NEUTRAL | 50 |
| `status=FAILED` | AI_CONTENT_ANALYSIS | AI_VALIDATION_SERVICE | AI_STATUS_FAILED | OPPOSES_PUBLISH | 75 |
| unknown status | AI_CONTENT_ANALYSIS | AI_VALIDATION_SERVICE | AI_STATUS_UNKNOWN | NEUTRAL | 0 |
| `emptySpaceConfidence` | AI_CONTENT_ANALYSIS | AI_VALIDATION_SERVICE | EMPTY_SPACE_CONFIDENCE | SUPPORTS_PUBLISH | field value |
| `legalRiskScore` | AI_CONTENT_ANALYSIS | AI_VALIDATION_SERVICE | LEGAL_RISK_SCORE | OPPOSES_PUBLISH | field value |
| `imageQualityScore` | AI_CONTENT_ANALYSIS | AI_VALIDATION_SERVICE | IMAGE_QUALITY_SCORE | SUPPORTS_PUBLISH | field value |
| `aiConfidence` | AI_CONTENT_ANALYSIS | AI_VALIDATION_SERVICE | AI_CONFIDENCE | NEUTRAL | field value |
| each `detectedRiskTypes[]` | AI_CONTENT_ANALYSIS | AI_VALIDATION_SERVICE | AI_RISK_{TYPE} | see normalizer | 40–90 |
| valid lat/lng | GEOSPATIAL_CONSISTENCY | PARKING_DOMAIN | COORDINATES_VALID | SUPPORTS_PUBLISH | 100 |
| invalid lat/lng | GEOSPATIAL_CONSISTENCY | PARKING_DOMAIN | COORDINATES_INVALID | OPPOSES_PUBLISH | 100 |
| `manualLocationEdited=true` | GEOSPATIAL_CONSISTENCY | PARKING_DOMAIN | MANUAL_LOCATION_EDITED | NEUTRAL | 50 |
| `legalStatus=ILLEGAL_OR_RISKY` | GEOSPATIAL_CONSISTENCY | PARKING_DOMAIN | SUBMITTER_LEGAL_RISK | OPPOSES_PUBLISH | 80 |
| `legalStatus=UNCERTAIN` | GEOSPATIAL_CONSISTENCY | PARKING_DOMAIN | SUBMITTER_LEGAL_UNCERTAIN | NEUTRAL | 50 |
| `legalStatus=LEGAL` (default) | GEOSPATIAL_CONSISTENCY | PARKING_DOMAIN | SUBMITTER_LEGAL_OK | SUPPORTS_PUBLISH | 30 |
| AI `eventId` | OPERATIONAL_PROVENANCE | SYSTEM | AI_EVENT_CORRELATED | SUPPORTS_PUBLISH | 100 |
| mediaId ≠ spot.mediaId | OPERATIONAL_PROVENANCE | SYSTEM | MEDIA_SPOT_MISMATCH | OPPOSES_PUBLISH | 100 |
| stale vs `moderationDecidedAt` | OPERATIONAL_PROVENANCE | SYSTEM | STALE_MODERATION_EVENT | NEUTRAL | 100 |