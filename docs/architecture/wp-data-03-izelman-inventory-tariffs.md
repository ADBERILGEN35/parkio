# DATA-WP-03 — İZELMAN inventory, roadside and tariffs

This work package adds an import foundation inside `parking-service`; it does not add
a service, scrape HTML, publish data, or claim live availability.

## Official sources and age

- Facility and roadside inventory: İzmir Open Data Portal / İZELMAN A.Ş., November
  2022. It is classified `HISTORICAL`.
- Parking tariffs: portal CSV dated 2 September 2024. It is classified `AGING` as of
  July 2026. CSV is the importer contract; an XLSX resource also exists.
- License: İzmir Metropolitan Municipality Open Data License.

Age is calculated from source-content time to fetch/import time. A new fetch never
makes old content `CURRENT`. Defaults are `AGING` after 180 days and `HISTORICAL`
after 730 days.

## Semantics and safety

Facility rows are official off-street inventory. Roadside rows are either `POINT`,
`STREET_NAME_ONLY`, or `UNKNOWN`; they are not individual parking-space geometry.
Neither inventory type supplies occupancy and `availableSpaces` is always unknown.
İZUM remains the only municipal live-occupancy source.

Tariff bands preserve decimal amounts and source text. Without explicit validity
evidence, a tariff is `UNKNOWN`; aged-out material is `HISTORICAL`. Neither may be
presented as a current guaranteed price.

CSV parsing supports UTF-8, UTF-8 BOM, and Windows-1254, detects comma/semicolon from
the header, and rejects formula-like cells. External IDs hash dataset key, normalized
name, six-decimal coordinates, and district.

## Flags and operator procedure

All `parkio.municipal.izelman.*` flags default false. Import, facility publication,
roadside publication, tariff publication, scheduling, candidate generation, and
auto-match are independent controls. Auto-match is intentionally unsupported.

1. Place a reviewed CSV under the configured read-only `allowed-input-dir`.
2. Enable only the required data-type import flag in a non-production environment.
3. Call `POST /api/v1/parking/municipal/sources/{sourceKey}/izelman-import?dryRun=true`
   with an `ADMIN` or `SUPER_ADMIN` role.
4. Review counts, age classification, rejects, and the persisted quality report.
5. Repeat without dry-run only after approval.

Complete successful snapshots may deactivate missing rows. Dry runs, partial input,
and failures never mass-deactivate. Publication must remain disabled until legal,
freshness, and product review is complete.

## Publication filter note

Publication decisions use stable `source_key` identity via `MunicipalSourcePublicationPolicy`.
Attribution text, publisher display labels, and dataset titles are never used to decide visibility.
IZUM attribution may mention IZELMAN in a legal disclaimer; that text must not hide live IZUM facilities.
Multi-source facilities remain visible when any linked publishable source remains enabled.
Unpublished IZELMAN inventory fields are gated independently from facility visibility.