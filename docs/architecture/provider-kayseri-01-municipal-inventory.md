# PROVIDER-KAYSERI-01 — Kayseri municipal parking inventory

## Source

- Dataset: [Kayseri Otoparklar](https://acikveri.kayseri.bel.tr/veri-seti/kayseri-otoparklar/35)
- Primary resource: UTF-8 GeoJSON `otoparklar-456371.geojson`
- Do **not** use the CSV export as primary: Turkish characters are mangled there
- Licence: portal [CC BY 4.0](https://acikveri.kayseri.bel.tr/sayfa/lisans/19) unless otherwise stated
- Grain: one GeoJSON feature → one facility (`CBNO`)
- Capability: `FACILITY_INVENTORY` only (no live occupancy / capacity field upstream)

## Identity

| Field | Value |
|-------|-------|
| Provider | `KAYSERI` |
| Source key | `kayseri-bb-otoparklar` |
| Display | Kayseri Büyükşehir Belediyesi |
| External ID | `CBNO` (string) |
| Reconciliation | `UPSERT_ONLY` |
| Cadence | 24h (`fixed-delay-ms=86400000`) |
| Flags | `PARKIO_MUNICIPAL_KAYSERI_ENABLED` / `_SCHEDULER_ENABLED` default **false** |

## Notes

- Capacity / opening hours / active flag absent upstream → canonical null / UNKNOWN
- Geo gate: lat 38.40–39.20, lng 35.00–36.20
- Konya remains in repo with flags OFF; no Cloudflare bypass
