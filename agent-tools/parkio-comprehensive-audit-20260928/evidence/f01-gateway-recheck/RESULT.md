# F-01 yeniden kontrolü (2877ec81)

2026-09-24 denetimindeki yeniden üretim testi, yeni kurucuya göre uyarlandı. Yeni kurucu `SessionEpochVerifier` ve `AccountStatusVerifier` alıyor; test bunlara mock veriyor. Test, `2877ec81`'in `git archive` kopyasında çalıştırıldı.

Komut:
```
./gradlew :services:gateway-service:test --tests '*AuditHeadBypassReproTest*' --no-parallel --console=plain -i
```

Çıktı (2026-09-28 ~07:24Z):
```
AUDIT GET status=401 UNAUTHORIZED
AUDIT HEAD status=401 UNAUTHORIZED
AUDIT POST status=401 UNAUTHORIZED
AUDIT OPTIONS status=401 UNAUTHORIZED
AUDIT PUT status=401 UNAUTHORIZED
AUDIT DELETE status=401 UNAUTHORIZED
AUDIT PREFLIGHT status=403 FORBIDDEN      (izin verilmeyen Origin ile CORS preflight)
AUDIT export() never invoked: OK
BUILD SUCCESSFUL in 4m 14s
```

Yan not: Testte kullanılan `GatewayErrorResponseWriter`, `JavaTimeModule` kayıtlı olmayan düz bir `ObjectMapper` ile kuruldu. Bu yüzden logda “Failed to serialize gateway error response” uyarısı görülüyor. Bu bir test kurulumu kalıntısıdır; üretimde Spring'in `ObjectMapper`'ı JSR-310 desteğini içerir. Bulgu değildir.

**Sonuç:** F-01 düzeltilmiştir. Pinli gateway (`f9710aa0`, `sha256:866a7fe0…`) bu kodu içeriyor; pinden sonra `services/gateway-service/src/main` altında değişiklik yok.
