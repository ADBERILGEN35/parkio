# 05 — Attribution ve DTO

## Presentation

`ParkingProviderCatalog.find(publishingKey)` → `displayName` + `attribution`

- IZUM satırı → IZUM catalog metni
- ISPARK satırı → ISPARK catalog metni
- Karışık yanıt → satır-özel (bleed yok)

## Public DTO

Değişmedi (provider-nötr):

`id, displayName, operatorName, facilityType, addressText, latitude, longitude, capacityTotal, availableSpaces, availabilityFreshness, dataUpdatedAt, sourceLabel, attribution`

`izumFoo` / `isparkFoo` eklenmedi.

## Frontend

Web/mobile generic `sourceLabel`/`attribution` kullanıyor; provider-specific UI branch eklenmedi.  
`PublicExplorePage` yorumu IZUM-only → provider-agnostic olarak güncellendi.
