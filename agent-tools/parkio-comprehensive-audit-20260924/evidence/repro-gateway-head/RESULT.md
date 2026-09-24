# F-01 yeniden üretim — anonim HEAD ile waitlist export

Kaynak: `aa865a25` `git archive` kopyası (scratch). Ürün worktree'sine dokunulmadı.
Test dosyası: `AuditHeadBypassReproTest.java` (kopyada `services/gateway-service/src/test/java/com/parkio/gateway/infrastructure/security/` altına yerleştirildi).

Komut:
```
./gradlew --stop; ./gradlew :services:gateway-service:test --tests '*AuditHeadBypassReproTest*' --no-parallel --console=plain -i
```
Çıktı (ilgili satırlar, 2026-09-24 ~07:45Z):
```
AUDIT GET  status=401 UNAUTHORIZED
AUDIT HEAD status=200 OK content-type=text/csv;charset=UTF-8 content-disposition=attachment; filename="parkio-waitlist-confirmed.csv"
BUILD SUCCESSFUL in 7m 3s
```
Test ayrıca `verify(service).export(any(), any())` (handler/sorgu çalıştı) ve `verifyNoInteractions(validator)` (JWT hiç doğrulanmadı) iddialarını geçti.
Kapsam: WebFlux controller + gerçek `WaitlistAdminSecurityWebFilter`; Caddy/ağ katmanı dahil değil (Caddyfile'da waitlist yollarına özel yöntem kısıtı bulunmadı).
İlk iki deneme Gradle ikili-depo hatası/buildSrc ile başarısız oldu (ortamsal); üçüncüsü `--no-parallel` ile geçti.
