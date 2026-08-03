# DATA-WP-13 — OSM Facility Display-Label Hygiene

> **Naming:** This package is **DATA-WP-13** (municipal/parking data). Document
> filenames follow `wp-data-13-*`. Unrelated to repository WP-13 if any.

## 1. Status

**Implementation complete (this package).** DATA-WP-13A (hosted-beta reimport leave-on
gate) is **not started**.

## 2. Executive summary

Public OSM facility labels previously fell back to technical strings such as
`OSM parking way/123456`. **DATA-WP-13** introduces a centralized, versioned policy
`osm-label-v1` that:

1. Prefers validated OSM name-bearing tags deterministically.
2. Falls back to readable Turkish neutral / operator / brand / type labels.
3. Never exposes node/way/relation IDs, raw external IDs, or technical source-key text
   as the public display name under `osm-label-v1`.
4. Writes `NAME` provenance **only** when the selected label came from a real
   name-bearing tag (`name:tr`, `name`, `official_name`, `short_name`).

Stable OSM external IDs (`node/{id}`, `way/{id}`, `relation/{id}`) and source links
remain unchanged. Ranking, linking, availability, duplicate-presentation, and
İZELMAN publication are out of scope.

## 3. Label policy version

| Version | Meaning |
|---------|---------|
| `osm-label-v1` | **Default.** Readable public labels; no technical OSM IDs. |
| `legacy` | Kill-switch. Restores prior `OSM parking {externalId}` synthetic labels when no validated `name` tag. |

Property: `parkio.municipal.osm.label-policy`  
Env: `PARKIO_MUNICIPAL_OSM_LABEL_POLICY`  
Canonical default: `osm-label-v1`  
Independent of import enablement, publication, conflation, and linking flags.

Implementation: `OsmDisplayLabelPolicy` (single source of truth). Do not duplicate in
parser or DTO mapping.

## 4. Tag precedence (`osm-label-v1`)

1. Valid localized Turkish name: `name:tr`
2. Valid general name: `name`
3. Valid official name: `official_name`
4. Valid short name: `short_name`
5. Operator-based readable label: `"<operator> Otoparkı"` (skip append when base already contains “otopark”)
6. Brand-based readable label: `"<brand> Otoparkı"` (same rule)
7. Facility-type-aware neutral fallback:
   - `Park Et ve Devam Et Otoparkı` (`park_ride=yes`)
   - `Yer Altı Otoparkı` (`underground=yes` / `parking=underground`)
   - `Katlı Otopark` (`parking=multi-storey` variants)
   - `Kapalı Otopark` (`covered=yes` / garage / building garage)
   - `Açık Otopark` (`covered=no` / `parking=surface|street_side`)
8. Neutral fallback: `Otopark`

Only tag values that pass validation are used. Official names are never fabricated.

## 5. Validation / rejection

Reject (ignore) candidates that are:

- blank / whitespace-only
- equal to the raw OSM external ID or `node|way|relation/{id}`
- prior technical prefixes (`OSM parking …`)
- placeholder / non-name literals (`yes`, `no`, `unknown`, `unnamed`, `parking`, `otopark`, …)
- URLs, phone-like, coordinate-like strings
- control-character-containing
- longer than **120** characters (bounded public label length)
- would compose duplicated generic prefixes (`Otopark Otopark…`)

Normalize safely: trim, collapse repeated whitespace, preserve Turkish characters and
legitimate punctuation, use `Locale.ROOT` for technical comparisons. Do not mutate
official source text beyond display-safe normalization.

## 6. Provenance behavior

| Selected from | `NAME` provenance | Notes |
|---------------|-------------------|-------|
| `name:tr` / `name` / `official_name` / `short_name` | **Yes** | Real name-bearing tags |
| operator fallback | **No** | `OPERATOR` may still be written when `operatorName` is present |
| brand fallback | **No** | Must **not** invent `OPERATOR` |
| type / neutral fallback | **No** | |
| `legacy` technical label | **No** | |

`ATTRIBUTION` provenance unchanged. Address never claimed by OSM.

**DATA-WP-14:** when a later successful OSM ingest selects a fallback (or otherwise
omits NAME from the supplied set), any previous **same-source** `NAME` provenance
row is withdrawn in the same facility transaction. Foreign sources are never deleted.
See [DATA-WP-14](wp-data-14-engineering-specification.md).

## 7. OSM import integration

Applied during accepted OSM import normalization (`OsmImportApplicationService`):

- External ID, source link, and facility identity unchanged
- Only `display_name` (and related provenance selection) may update
- Complete successful reimport refreshes labels under the active policy
- Failed parse/import before facility loop does not mutate labels/provenance
- Per-facility persist remains `REQUIRES_NEW` (facility + link + provenance atomic)
- Soft-deactivation only after complete successful import (unchanged)
- Idempotent repeated import (no row growth; unchanged counts)

Does **not** change: access filtering, clip, publication, availability, candidate
generation, duplicate-presentation thresholds, provenance publication semantics.

## 8. Metrics

Counter `parkio.municipal.osm.label` with bounded labels only:

- `outcome` — `real_name_selected`, `localized_name_selected`, `operator_fallback`,
  `brand_fallback`, `type_fallback`, `neutral_fallback`, `invalid_name_rejected`,
  `technical_id_removed`, `unchanged`, `legacy_technical`
- `source_family` — `osm`
- `policy_version` — `osm-label-v1` | `legacy`
- `fallback_type` — bounded enum string

Never label with facility ID, external ID, name/operator text, coordinates, or request ID.

Quality report JSON on import runs includes `labelPolicyVersion` and `labelOutcomes`.
Facility metadata may record `labelPolicyVersion` / `labelOutcome` (bounded).

## 9. Public API impact

**No DTO shape change.** Clients continue to read `displayName` on nearby/detail.
Semantics under `osm-label-v1`: values are human-readable and never synthetic
`OSM parking {element}/{id}` strings. Availability remains null for OSM;
attribution unchanged.

## 10. Database

**No Flyway migration.** Tip remains V33. Policy version is recorded in import quality
evidence / metadata only.

## 11. Rollback

Set `PARKIO_MUNICIPAL_OSM_LABEL_POLICY=legacy` and reimport to restore legacy synthetic
labels for unnamed features. No schema rollback. Does not affect linking or ranking.

## 12. Non-goals / DATA-WP-13A

Out of scope: registry linking, source merge, ranking, availability synthesis,
frontend work, fabricating official names, Flyway migration.

**DATA-WP-13A** (hosted-beta reimport gate) is a separate leave-on package and is
**not started** by this work.
