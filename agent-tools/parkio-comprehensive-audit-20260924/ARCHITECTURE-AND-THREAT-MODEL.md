# Mimari ve Tehdit Modeli — Parkio (`aa865a25`)

Kaynak: kod, compose, workflow ve betiklerden türetilmiştir. Canlı topoloji gözlenmedi. Üretim host'u belgelerde “parkio-civo-prod” (Civo VPS) olarak anılıyor; compose dosya adları ise “azure-hosted-beta” — isimlendirme sapması (F-12).

## 1. Sistem haritası

```
İnternet
  │
  ├── parkio.dev (Hostinger, statik)  web/marketing  ──POST /api/v1/waitlist…──┐
  │                                                                            │
  └── Caddy (tek public konteyner, 80/443, TLS, CSP/HSTS, /actuator/{info,env,configprops} engeli)
        ├── app.*  → web (nginx, SPA + admin UI, pinli digest)
        └── api.*  → gateway-service (Spring Cloud Gateway, WebFlux; JWT RS256 doğrulama, rate limit [Redis],
                      X-User-* temizleme/enjeksiyon, X-Gateway-Auth ekleme)
                      │  + GATEWAY'İN KENDİ İŞ MANTIĞI: waitlist (postgres-gateway), waitlist admin/export,
                      │    waitlist ops outbox → dosya exporter (inbox dizini)
                      ├── auth-service        (postgres-auth)      JWT imzalama, JWKS, kayıt/davetiye, reset, admin, erasure koordinasyonu
                      ├── user-service        (postgres-user)      profil, kayıtlı yerler, smart-return (flag)
                      ├── parking-service     (postgres-parking, PostGIS)  public explore, belediye senkronu (İZUM/İSPARK/ANPARK/Konya/Kayseri/OSM/İZELMAN), topluluk spotları, oturumlar
                      ├── media-service       (postgres-media) + MinIO + ClamAV
                      ├── gamification-service (postgres-gamification)
                      ├── notification-service (postgres-notification)
                      ├── moderation-service  (postgres-moderation)
                      ├── ai-validation-service (postgres-ai-validation; Gemini opsiyonel)
                      └── analytics-service   (postgres-analytics)
  Kafka (KRaft, PLAINTEXT, tek broker) — servisler arası olaylar; her serviste outbox relay + inbox dedup + DLT
  Redis — rate limit, login kilidi, idempotency, cooldown

Host dışı süreçler (systemd, Civo)
  - slack_biz relay: waitlist inbox dizini → SQLite kuyruk (WAL) → worker → Slack webhook
  - New Relic log pilotu: fluent-bit → budget_gate.py (SQLite defter) → NR
  - backup cron: run-production-backup.sh → backup-hosted-beta.sh (pg_dump ×10 + erasure ledger + MinIO mirror → openssl → offsite mc/Azure)

İzleme: Prometheus, node/kafka/blackbox exporter, Grafana; Alertmanager/Loki/Promtail/Tempo üretim setinde profil ile KAPALI (F-04)
CI/CD: GitHub Actions (api dalı), GHCR imajları, self-hosted runner'lar (parkio-beta, invite-production)
Mobil: frontend/apps/mobile-v2 (Expo, kanonik), apps/mobile (legacy, advisory)
```

## 2. Bileşen tablosu

| Bileşen | Amaç / sahiplik | Girdi → çıktı / bağımlılık | Ağ maruziyeti | AuthN/AuthZ | Veri & hassasiyet | Kalıcı durum | Hata davranışı | Bayraklar | Test / deploy yolu |
|---|---|---|---|---|---|---|---|---|---|
| Pazarlama sitesi | Tanıtım + waitlist formu | Form → gateway `/api/v1/waitlist` | Hostinger public | Yok (public), rıza yalnız istemci tarafı (F-18) | Ad, e-posta, şehir, rıza zamanı | Yok (localStorage dil) | 503 yanlış eşlenir (F-19) | — | `node --test` 17/17; zip/dist elle paket |
| Web SPA + admin | Kullanıcı ve admin UI | Gateway API | Caddy arkası | Access token bellekte, refresh HttpOnly cookie; istemci rota koruması + sunucu kontrolü | Hesap, konum | SW önbelleği, localStorage dil | Harita hatası sessiz (F-20) | `VITE_*` build-time; public explore/municipal bake env | vitest 878/879 (+PNG ile PASS), Playwright; pinli digest imaj |
| Gateway | Kenar yönlendirme + waitlist | Tüm API | Yalnız Caddy'den | RS256 JWKS, rota rol kuralları; yerel controller'lar ayrı WebFilter (F-01, F-13) | Waitlist PII | postgres-gateway, export dizini | Rate limiter Redis'siz fail-open; epoch/status fail-closed (503) | waitlist admissions, ops notifications export, pause file (#104) | Unit + Testcontainers; pin `031d4834` |
| auth-service | Kimlik | Gateway, Kafka, Resend | İç ağ | JWT imzalama; admin kararı başlıktan (F-16) | E-posta, parola hash, token hash'leri | postgres-auth, Redis | Resend senkron (F-14) | `PARKIO_REGISTRATION_MODE` (varsayılan closed), invite | Pin `b10c1f7c` |
| parking-service | Keşif, belediye verisi, topluluk | Gateway, belediye API'leri, Kafka | İç ağ + dışarıya belediye egress | X-Gateway-Auth + X-User-* | Konum, arama logları (F-17) | PostGIS | Besleme boyut sınırı yok (F-23) | public explore, municipal sources, smart-return | Pin (kaynak belgesiz) |
| media-service | Görsel yükleme | Gateway, MinIO, ClamAV | İç ağ | Sahip kontrolü | Görseller (EXIF temizlenir) | MinIO, postgres-media | ClamAV fail-closed | scan enabled | Pin (kaynak belgesiz) |
| user/gamification/notification/moderation/ai-validation/analytics | Destek alanları | Kafka + gateway | İç ağ | Aynı | Profil, puan, bildirim, vaka | Kendi Postgres'leri | FixedBackOff(500ms,2) → DLT | çeşitli | **Pinsiz, host'ta build** (F-11) |
| Kafka | Olay omurgası | Outbox relay'ler | İç ağ, port yayınlanmıyor | **Yok** (PLAINTEXT, ACL yok) | Olay payload'ları (kullanıcı id'leri) | Topic log'ları (7 gün/2 GB) | — | — | Compose runtime validation |
| Redis | Limit, kilit, idempotency | Gateway, auth | İç ağ | Parola (preflight zorunlu) | Oturum dışı metaveri | AOF | Auth tarafı fail-closed, gateway limiter fail-open | — | — |
| slack_biz relay | Waitlist/kayıt bildirimleri | Inbox dosyaları → Slack | Dışa HTTPS | Webhook URL sırrı | Ad (display name), sayımlar | SQLite (WAL), `.acked/` | Crash-loop (F-09) | `PARKIO_SLACK_BIZ_ENABLED` | Python kabul testleri (mock) |
| NR log pilotu | Log gönderimi, bütçe | fluent-bit → gate → NR | Dışa HTTPS | Lisans anahtarı | Redakte loglar | SQLite defter (yedeksiz, F-25) | Defter yoksa 0'dan başlar | test control off | Python testleri |
| Yedekleme | DR | pg_dump, MinIO, ledger → offsite | Dışa (mc/Azure) | Offsite kimlik bilgisi | Tüm veri (şifreli) | Yerel damgalar 14 gün | MinIO hatasında COMPLETE (F-02) | `BACKUP_PRODUCTION_MODE` | Sentetik CI drill'leri |
| CI/CD | Build/test/deploy | GitHub | — | `GITHUB_TOKEN`, ortamlar, self-hosted runner | Artifact'lar | GHCR | Zamanlanmışlar master'da (F-07) | `vars.PUBLISH_IMAGES`, `CODEQL_ENABLED` | — |

## 3. Tehdit modeli (özet)

**Varlıklar:** kullanıcı hesapları ve kimlik bilgileri; waitlist PII (ad, e-posta, şehir, rıza); hassas konum geçmişi (arama/görüntüleme logları, smart-return ev koordinatları); topluluk içeriği/medya; JWT imzalama anahtarı ve iç paylaşılan sır; yedekler ve offsite kimlik bilgileri; Slack webhook, NR lisansı, MapTiler anahtarı; üretim deploy yetkisi.

**Aktörler:** anonim internet kullanıcısı; kayıtlı kullanıcı; kötü niyetli kayıtlı kullanıcı/çift hesap; moderatör/admin (ve kötüye kullanan ya da ele geçirilmiş admin); ele geçirilmiş iç servis/konteyner; kötü niyetli/bozuk belediye veri kaynağı; depo yazma yetkilisi / CI; operatör (hata yapan).

**Güven sınırları:**
1. İnternet ↔ Caddy (TLS, CSP, yol engelleri)
2. Caddy ↔ gateway (X-Forwarded-* yalnızca güvenilir proxy CIDR'larından)
3. Gateway ↔ servisler (tek paylaşılan `X-Gateway-Auth` + düz X-User-* başlıkları) — **en zayıf iç sınır** (F-16)
4. Servisler ↔ Kafka (kimliksiz)
5. Host ↔ systemd süreçleri (dosya sistemi inbox dizini, SQLite)
6. Host ↔ offsite depolama
7. GitHub ↔ self-hosted runner'lar ↔ üretim host'u
8. parking-service ↔ belediye API'leri (güvenilmeyen veri girişi)

**Giriş noktaları:** `/api/v1/public/explore/*`, `/api/v1/public/geocoding/*`, `/api/v1/auth/*`, `/api/v1/waitlist*` (public + admin), kimlikli `/api/v1/*`, `/actuator/*` (F-28), pazarlama formu, e-posta linkleri (`?token=&lang=`), mobil deep link'ler, belediye yanıtları, Slack/NR dış çağrıları, workflow_dispatch girdileri.

**Kötüye kullanım senaryoları (bulgulara eşleme):**
| Senaryo | Sınır | Durum |
|---|---|---|
| Anonim kullanıcı admin export'unu yöntem hilesiyle tetikler | 1–2 | **Mümkün (F-01)**, satır verisi olmadan |
| Kapalı beta sırasında e-posta tespiti | 1 | Mümkün, INVITE modunda (F-14) |
| Hedef hesabı kilitleme | 1 | Mümkün (F-15) |
| İptal edilen admin'in 15 dk daha export alması | 1–2 | Mümkün (F-13) |
| İç konteyner ele geçirilince SUPER_ADMIN taklidi / sahte olaylar | 3–4 | Mimari olarak mümkün (F-16) |
| Bozuk belediye yanıtıyla OOM | 8 | Mümkün (F-23) |
| Bozuk Slack yanıtıyla sonsuz yeniden gönderim | 5 | Doğrulandı (F-09) |
| Sentetik anahtarlı web imajının üretime çıkması | 7 | Guard etkisiz (F-05) |
| Eksik yedeğin COMPLETE sayılıp geri yüklenmesi; silinmiş kullanıcının geri gelmesi | 6 | Doğrulandı (F-02, F-03) |
| Uyarıların kimseye ulaşmaması | izleme | Config'te doğrulandı (F-04) |

**Yönetim/kurtarma yolları:** `bootstrap-super-admin.sh` (varsayılan kapalı, token'lı), invite oluşturma (operatör token'ı), `parkio-prod-compose.sh` (guard, F-05), rollback betikleri + CI rollback (F-06), `restore-hosted-beta.sh` (F-03), `outbox-deadletter-recovery.sh` / `dlt-redrive` (F-24, F-32), #104 kurtarma koordinasyonu (bekliyor).

## 4. Mimari değerlendirme notları

- **Hizmet ayrıştırması:** 10 servis + 10 ayrı Postgres + Kafka + Redis + MinIO + ClamAV + izleme tek bir VPS üzerinde (üretim setinde 32 servis; `mem_limit` toplamları kabaca 640–1280 MB/servis). Kapalı/davetiyeli beta ölçeğinde bu ayrıştırma, bağımsız ölçekleme faydası sağlamadan: (a) 10 ayrı erasure işleyicisi ve ack koordinasyonu (F-10), (b) 8 kopya outbox relay (aynı zehirli payload kusuru, F-32), (c) 6 pinsiz imaj (F-11), (d) tek paylaşılan sırra dayanan güven modeli (F-16) maliyetleri doğuruyor. Bu “mikroservis yanlış” demek değildir; somut bakım maliyeti yukarıdaki bulgulardır.
- **Waitlist'in gateway içinde olması:** Kenar bileşen kendi veritabanına ve iş mantığına sahip; yerel controller'lar gateway'in GlobalFilter zincirinden (rate limit, epoch, status) faydalanmıyor ve ayrı bir güvenlik filtresi gerekiyor — F-01 ve F-13'ün doğrudan kök nedeni.
- **Paylaşılan kod yok (bilinçli):** `settings.gradle.kts` “servisler model paylaşmaz” diyor; sonuç olarak GatewayAuthFilter, CorrelationIdFilter, outbox relay, erasure handler her serviste kopya — düzeltmeler 6–10 yerde yapılmalı.
- **Yapılandırma karmaşıklığı:** 22+ compose dosyası, çok sayıda overlay, host'a özgü dosyalar; kanonik set tek dosyada tanımlanmış (iyi) ama tüm betikler onu kullanmıyor (F-12).
- **İki dal modeli:** Varsayılan `master` bayat; tüm geliştirme `api`'de — GitHub'ın zamanlama ve Dependabot varsayımlarıyla çatışıyor (F-07).
- **Belgelenme:** Operasyon belgeleri çok ayrıntılı ve kendi boşluklarını dürüstçe listeliyor (ör. BRR-01 G10), fakat birbirleriyle çelişen RPO/RTO değerleri var (F-27) ve çok sayıda ajan raporu (`agent-tools/`) iddia/kanıt ayrımını zorlaştırıyor.
- **Operabilite:** Asıl geliştirici dışında birinin sistemi işletmesi için elle adımlar (Alertmanager başlatma, recovery overlay, cron) depoda tam olarak kodlanmamış.
