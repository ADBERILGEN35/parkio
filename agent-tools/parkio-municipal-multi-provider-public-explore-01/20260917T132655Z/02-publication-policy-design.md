# 02 — Publication Policy Tasarımı

## Kapılar (AND)

1. **SUPPORTED_BY_CODE** — adapter + catalog kaydı
2. **REVIEWED_FOR_PUBLICATION** — `PublicExplorePublicationPolicy.ReviewedPublicFamily` ∈ {IZUM, ISPARK}
3. **ENABLED_BY_ENV** — `PARKIO_PUBLIC_EXPLORE_ALLOWED_SOURCE_FAMILIES`
4. **SOURCE / FACILITY ELIGIBLE** — active link, active facility, geçerli lat/lng, radius

## Sınıf

`com.parkio.parking.externalsource.PublicExplorePublicationPolicy`

- Bilinmeyen token → fail closed
- Bilinen ama unreviewed (ANPARK, KONYA, KAYSERI, IZELMAN, OSM, …) → fail closed
- Boş allowlist → startup OK, municipal satır yok (mevcut fail-closed veri semantiği)
- Family → source key(s): şu an 1:1; API yine de `Set<sourceKey>` alır (çoklu key aileleri için hazır)

## Env örnekleri

| CFG | Değer | Beklenen |
|-----|-------|----------|
| A | `IZUM` | PASS |
| B | `ISPARK` | PASS |
| C | `IZUM,ISPARK` | PASS |
| D | `FOO` | FAIL |
| E | `ANPARK` | FAIL (unreviewed) |
| F | boş | no municipal rows |
| G | whitespace/duplicate | deterministic dedupe |
| H | ingest ON, publish OFF | NOT PUBLIC |
| I | publish ON, no rows | empty for that source, no crash |
