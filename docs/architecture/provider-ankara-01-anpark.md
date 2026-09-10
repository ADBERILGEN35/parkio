# PROVIDER-ANKARA-01 — Ankara / ANPARK Municipal Provider

Inventory-only municipal parking provider for Ankara Büyükşehir Belediyesi / ANPARK
(BELTAŞ A.Ş. brand). **No live occupancy.**

## Upstream contract

```
GET https://www.anpark.com.tr/wp-json/anpark/v1/parks
```

Fields: `id`, `name`, `type`, `district`, `lat`, `lng`, `capacity`, `schedule`,
`address`, `active`.

List-only. No detail fan-out. No payment/debt endpoints.

## Identity

| Concept | Value |
|---------|--------|
| Provider ID | `ANPARK` |
| Source key | `ankara-anpark-parks` |
| API display label | `Ankara Buyuksehir Belediyesi / ANPARK` |
| UI label (TR) | `Ankara Büyükşehir Belediyesi / ANPARK` |
| Operator | BELTAŞ A.Ş. / ANPARK |

Never expose `ankara-anpark-parks` or `wp-json` in product UI.

## Capabilities

- `FACILITY_INVENTORY` — yes
- `LIVE_OCCUPANCY` — **no** (adapter returns empty occupancy; do not invent from capacity)

## Type mapping

| Upstream | Canonical |
|----------|-----------|
| `yolustu` | `ON_STREET` |
| `acik` | `OFF_STREET` |
| `kapali` | `OFF_STREET` |
| `rekreasyon` | `OFF_STREET` |
| other | `UNKNOWN` |

## Capacity=0 policy

Upstream recreation sites report `capacity=0`. Canonical capacity becomes **null**
(unknown), matching OSM static inventory. Never map to `availableSpaces=0` /
`occupiedSpaces=0`.

Negative capacity → record rejected.

## Active / inactive

`active=false` rows are excluded from the sync payload before counting so they are
treated as absent for `AUTHORITATIVE_FULL_SET` soft-deactivation without inflating
reject counts. Failed/empty/partial feeds never mass-deactivate.

## Reconciliation

`AUTHORITATIVE_FULL_SET` (source-scoped). Same safety gates as İSPARK/İZUM.

## Config (defaults dark)

```
PARKIO_MUNICIPAL_ANPARK_ENABLED=false
PARKIO_MUNICIPAL_ANPARK_SCHEDULER_ENABLED=false
PARKIO_MUNICIPAL_ANPARK_FIXED_DELAY_MS=21600000
```

Default poll interval **6 hours** (inventory-only; no live cadence).

## Legal

**LEGAL REVIEW REQUIRED** before production enablement.
`production_approved=false` in Flyway seed; catalog `productionEligible=false`.
Do not claim open-data / unrestricted redistribution rights.

## Hosted-beta

Compose passthrough mirrors İSPARK. Enable only after CI green:

```
PARKIO_MUNICIPAL_ANPARK_ENABLED=true
PARKIO_MUNICIPAL_ANPARK_SCHEDULER_ENABLED=true
```

Rollback: set both to `false` and recreate parking-service if needed.
