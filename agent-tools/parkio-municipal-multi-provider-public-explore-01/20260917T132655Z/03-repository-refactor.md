# 03 — Repository Refaktör

## Kaldırılan IZUM-özel API

- `publicExploreIzumNearby`
- `countPublicExploreIzumNearby`
- `findPublicExploreIzumById`

## Yeni generic API

- `publicExploreNearby(..., Set<String> allowedSourceKeys)`
- `countPublicExploreNearby(..., Set<String> allowedSourceKeys)`
- `findPublicExploreById(..., Set<String> allowedSourceKeys)`

## SQL

- Hard-coded `source_key='izmir-izum-otoparklar'` kaldırıldı
- `s.source_key IN (:sourceKeys)` — JdbcClient parametre bağlama (SQL concatenation yok)
- Boş set → Java early-return (boş IN yok)
- `DISTINCT ON (f.id)` — çoklu allowed source linklerinde çift satır yok
- Global `ORDER BY dist ASC, id ASC` + `LIMIT LEAST(:limit, 6)`
- Count: `count(DISTINCT f.id)`

## Korunan kurallar

- Radius tavanı 5000 m
- Limit tavanı 6 (global, provider başına değil)
- Active + publishable link + geçerli koordinat + `ST_DWithin`
- İzmir merkez yalnızca client fallback; sunucu lat/lng onurlar

## DB migration

**0** — şema zaten `source_key` / link / occupancy ilişkisini destekliyor.
