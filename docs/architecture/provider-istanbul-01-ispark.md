# PROVIDER-ISTANBUL-01 — İSPARK Integration

First real post–WP-SPA-13 municipal live provider: İstanbul / İSPARK.

## Official source

- Portal: İBB Açık Veri — İSPARK otopark listesi / detay web servisleri
- Configured list endpoint: `{base-url}{path}` default `https://api.ibb.gov.tr` + `/ispark/Park`
- License: İBB Açık Veri (attribution required; as-is). Not legal advice.

## Product presentation

Canonical user-facing label (clients localize diacritics):

**İstanbul Büyükşehir Belediyesi / İSPARK**

Never expose `istanbul-ispark-parks`, `ISPARK` enum, or raw API paths.

## Architecture

```
İSPARK /ispark/Park (list only)
→ IsparkParkingClient
→ IsparkMunicipalParkingAdapter
→ validate / normalize
→ MunicipalFacilitySyncService (AUTHORITATIVE_FULL_SET)
→ source links (parkID) + occupancy snapshots
→ existing nearby / recommendations / ranking / UX
```

### List vs detail

**List-only v1.** List supplies identity, coords, capacity, emptyCapacity, type, district, hours, isOpen.
Detail (`ParkDetay`) is not fetched every sync (would be ~250 extra requests). Freshness uses **FETCH** provenance (same pattern as İZUM). Address uses `district` from the list.

### Flags

```yaml
parkio.municipal.ispark.enabled: false          # default dark
parkio.municipal.ispark.scheduler-enabled: false
```

Independently controllable from İZUM. Disable stops sync; does not hard-delete source-scoped rows. Publication follows family policy (ISPARK publishable like İZUM).

Hosted-beta (`docker-compose.azure-hosted-beta.yml`) must map `PARKIO_MUNICIPAL_ISPARK_*`
into parking-service (same pattern as İZUM). Without Compose passthrough, env-file values
never reach the container. Example defaults live in `docker/.env.azure-hosted-beta.example`.

### Reconciliation

`AUTHORITATIVE_FULL_SET` after successful non-empty validated feed. Soft-deactivate missing İSPARK links only. Failures / empty / partial never mass-deactivate. Large-shrink warning retained from WP-SPA-13.

## Rollback

1. Set `parkio.municipal.ispark.enabled=false` (and scheduler false)
2. Redeploy prior SHA only if code rollback required
3. Do not hard-delete provider data as first step
