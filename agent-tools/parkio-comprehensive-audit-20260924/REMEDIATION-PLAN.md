# REMEDIATION-PLAN — Parkio (`aa865a25`)

İlke: önce veri kaybını/sessiz arızayı ve kimliksiz erişimi kapat; sonra kurtarılabilirlik ve sürüm güvenliği; sonra gizlilik/UX; en son sertleştirme. Açık PR'larla çakışan işler ayrıca işaretlendi (**#104 aktif; o dallara dokunulmamalı**).

## P0 — Sınırlı beta devam etmeden önce

| # | İş | Bulgular | Kabul kriteri | Bağımlılık | Efor | Çakışma |
|---|---|---|---|---|---|---|
| P0-1 | Waitlist admin filtresini yöntemden bağımsız yap (HEAD/OPTIONS dâhil 401), aynı değişiklikte epoch + hesap durumu kontrolü ekle; gateway imajını yeniden yayınla ve pinle | F-01, F-13 (waitlist kısmı) | `AuditHeadBypassReproTest` tersine döner (HEAD → 401, repository çağrılmaz); iptal edilmiş token → 401 | — | S | #104 aynı serviste `waitlist/ops`'a dokunuyor, bu filtreye değil — düşük risk |
| P0-2 | COMPLETE kapısına MinIO sonucunu ekle; başarısızlıkta kısmi `minio/` sil; `backup-databases.sh`'yi kapıya bağla; budamayı başarıya koşullu yap; **gerçek betikleri stub'larla uçtan uca çalıştıran** test | F-02, F-26(4) | Harness h1/h2 → COMPLETE yok, `mc` çağrısı yok, çıkış ≠0; son iyi damga korunur | — | S–M | **#104 `backup-hosted-beta.sh`'ye dokunuyor** → #104 sahibiyle sıralama: ya #104'ten önce küçük bağımsız PR, ya #104'e rebase |
| P0-3 | Canlı uyarı teslimini doğrula (salt okunur host kontrolü: alertmanager çalışıyor mu, Prometheus hedefi aktif mi); ardından Alertmanager'ı kanonik üretim setine taşı + deploy sonrası smoke + Watchdog/dead-man's switch | F-04 | Üretim seti config render'ında alertmanager var; sentetik uyarı Slack test kanalına ulaşır | Host erişimi olan operatör | S | Yok |
| P0-4 | Son 7 günün yedek manifestlerinde `minioOk` ve `success` değerlerini elle kontrol et; offsite'ta COMPLETE'i olup `minioOk=0` olan damgaları “kullanma” olarak işaretle | F-02, F-03 | Kayıt altına alınmış kontrol listesi | Operatör | S | Yok |

## P1 — Geniş sürümden önce (sınırlı beta sırasında paralel)

| # | İş | Bulgular | Kabul kriteri | Bağımlılık | Efor | Çakışma |
|---|---|---|---|---|---|---|
| P1-1 | Güvenli restore yolu: `restore-hosted-beta.sh` içinde `verify_stamp`, `restore-stamp-preflight.py`, `success=1` şartı, ledger birleştirme; `backup-last-good.json`; runbook güncelle | F-03 | Başarısız manifestle restore reddedilir; damga sonrası silinen sentetik kullanıcı geri gelmez | P0-2 | M | #102/#104 (off-host erasure) — birlikte tasarla |
| P1-2 | Deploy guard'ı düzelt: `bash` ile çağır/+x; pinli digest'ten bundle çıkarıp desen tabanlı ret; testi CI'a ekle | F-05, F-26(3) | CI'da guard testi PASS; 8 varyantın hepsi reddedilir | — | S–M | Yok |
| P1-3 | CI rollback: `download-artifact` için `run-id` + `github-token` + `actions: read` | F-06 | Staging'de önceki koşu artifact'ıyla rollback başarılı | — | S | Yok |
| P1-4 | Zamanlanmış workflow'ları api'ye yönlendir (dispatcher genişlet veya `ref: api`); Dependabot `target-branch: api`; bayat master PR'larını kapat/yeniden hedefle | F-07 | Pazartesi security-ci `head_sha == origin/api`; yeni Dependabot PR'ları api tabanlı | — | S | `master` değişikliği gerektirir (dispatcher orada) |
| P1-5 | CodeQL'in 4 “high” uyarısını triage et; gerçek olanları bu plana ekle | F-08 | Her uyarı için karar kaydı | Code scanning erişimi | S | — |
| P1-6 | Slack relay: `HTTPException` → AMBIGUOUS, worker catch-all, lease reclaim'de `attempts++` | F-09 | `poison_worker.py` ≤ MAX_ATTEMPTS gönderim, `delivery_unknown`, çökme yok | — | S | #104 relay kurulum/uzlaştırmaya dokunuyor; `transport.py`/`worker.py`/`store.py`'ye değil |
| P1-7 | Erasure ack'ini commit sonrasına/outbox'a taşı; uzun backoff; tombstone kontrolü; serbest metin temizliği (6 servis) | F-10 | Ack hata enjeksiyonu testi; commit hatasında ack yok | Ortak desen kararı | M | #102/#104 ile kavramsal örtüşme |
| P1-8 | Tüm 10 servis için CI'da build edilmiş digest pinli imajlar; pin dosyalarında kaynak SHA + config ID; deploy'da imza/attestation doğrulaması; temel imajları digest'e pinle | F-11 | Üretim seti render'ında `build:` yok; tüm `image:` `@sha256:` | P1-9 | M | #56 (release artifact kabulü) |
| P1-9 | Tek compose dosya listesi: `deploy-common.sh`, rollback, DR runbook aynı seti kullansın; CI'da set eşitliği testi | F-12 | Tüm yollar aynı config hash'i üretir | — | S–M | Yok |
| P1-10 | Gerçek (şifreli) damga ile izole restore drill'i, ölçülmüş RTO/RPO'nun tek belgede yayımlanması; drill-01 parity FAIL'in kök nedeni | F-27 | Tarihli drill kaydı, ölçülmüş süreler | P1-1 | M | #104 kurtarma koordinasyonu |

## P2 — Geniş sürüm için güvenlik/gizlilik/UX

| # | İş | Bulgular | Kabul kriteri | Efor |
|---|---|---|---|---|
| P2-1 | Rol iptali/admin oturum iptalinde epoch artışı | F-13 | Eski token 30 sn içinde 401 | S |
| P2-2 | Enumeration: önce davetiye doğrulama; sahte BCrypt; asenkron e-posta; waitlist resend hep 202 | F-14 | Aynı yanıt + gecikme farkı < ~10 ms | S–M |
| P2-3 | Hesap kilidi: hesap+IP anahtarı, kademeli gecikme, sıfırlamada temizleme | F-15 | Farklı IP'den doğru giriş başarılı | S–M |
| P2-4 | auth-service admin yetkisini JWT'den al; iç kimlik yol haritası (servis başına kimlik/mTLS, Kafka SASL+ACL) | F-16 | Sahte `X-User-Roles` → 403 | S / L |
| P2-5 | Konum logları TTL + indeks | F-17 | Eski satırlar silinir | S |
| P2-6 | Rıza: zorunlu `consent:true` + metin sürümü; export'a `confirmed_at` | F-18 | Eksik rıza → 400 | S–M |
| P2-7 | Pazarlama waitlist hata eşlemesi | F-19 | Kodsuz/HTML 503 → sunucu hatası mesajı | S |
| P2-8 | Harita hata banner'ı + liste yönlendirme; Explore retry; atıf yerleşimi | F-20 | Playwright 403/503 senaryoları | S–M |
| P2-9 | Tüm rotalarda URL `lang` önceliği | F-21 | `/reset-password?lang=en` + kayıtlı `tr` → EN | S |
| P2-10 | Doluluk: kapalı/null → UNAVAILABLE; kaynak zamanı yoksa freshness tavanı; ardışık İZUM atlama uyarısı | F-22 | Birim testleri + metrik | S–M |
| P2-11 | Belediye istemcilerinde boyut sınırı + toplam deadline | F-23 | WireMock testleri | S–M |
| P2-12 | DLT redrive orijinal topic filtresi + offset takibi | F-24 | Karışık DLT testi | S |
| P2-13 | Operasyonel durum (NR defteri, relay SQLite) yedek/kurtarma — #101–#104'ün kapsam içinde incelenip birleştirilmesi; üretimde defter yoksa fail-closed | F-25 | Defter yoksa gate başlamaz; restore sonrası harcama korunur | M |
| P2-14 | CI yanlış kırmızı/yeşil düzeltmeleri (slack_biz merge-base, expo-doctor bloklayıcı) | F-26 | PR #44'te slack_biz yeşil/açık atlama | S |
| P2-15 | `/actuator/*` (health hariç) Caddy'de engelle | F-28 | public `/actuator/prometheus` 404 | S |
| P2-16 | Konteyner sertleştirme (docker.sock, cap_drop, loopback bind, chmod) | F-29 | Config lint testi | S–M |
| P2-17 | Pazarlama kontrast + `lang` + footer i18n | F-30 | axe color-contrast 0 | S |

## P3 — Sertleştirme ve borç

F-31 (oyunlaştırma/moderasyon kötüye kullanım kontrolleri — özellik açılmadan önce P2'ye yükselir), F-32 (outbox zehirli payload, 8 servis; notification dead-letter kurtarma), F-33 (waitlist CAS), F-34 (sayfalama/CSV), F-35 (doğrulama token'ı temizleme + tek çağrı), F-36 (72 bayt), F-37 (correlation-id doğrulama), F-38 (tz sütunu, lat/lng, N+1, checksum, minioadmin), F-39 (CSP/SW/URL PII/source map), F-40 (SHA pinleme, `env:` ile girdi). Ek küçük öneriler: waitlist ops `record_failed` metriği için uyarı kuralı; exporter'ın isim arama hatasında “Ad belirtilmemiş” yerine yeniden deneme; Slack isim alanında çıplak alan adı reddi.

## Önerilen çakışmasız iş akışları

| Akış | Kapsam | Dokunduğu yerler | #104 ile çakışma |
|---|---|---|---|
| **WS-A: Gateway erişim kontrolü** | P0-1, P2-1, P2-15, F-34 | `services/gateway-service/.../security`, `presentation/waitlist`, `persistence/waitlist`, `auth-service/.../admin`, Caddyfile | Yok (#104 yalnızca `infrastructure/waitlist/ops` + `application.yml` — `application.yml` değişikliğinde dikkat) |
| **WS-B: Yedekleme/DR bütünlüğü** | P0-2, P0-4, P1-1, P1-10 | `scripts/backup-*.sh`, `scripts/lib/backup-common.sh`, `restore-hosted-beta.sh`, runbook | **Var** (`backup-hosted-beta.sh`, `restore-drill-01.sh`) — #104 sahibiyle sıralama zorunlu |
| **WS-C: Sürüm/deploy güvenliği** | P1-2, P1-3, P1-8, P1-9, P0-3 (compose kısmı) | `scripts/guard-*`, `parkio-prod-compose.sh`, `deploy-common.sh`, compose dosyaları, deploy workflow'ları | Düşük (#104 `docker-compose.waitlist-ops-inbox.yml`'e dokunuyor) |
| **WS-D: CI hijyeni** | P1-4, P1-5, P2-14, F-40 | `.github/workflows/*`, `dependabot.yml`, `master` dispatcher | #104 iki yeni workflow ekliyor — farklı dosyalar |
| **WS-E: Relay/olay tutarlılığı** | P1-6, P2-12, F-32 | `scripts/slack_biz/{transport,delivery,worker,store}.py`, `tools/dlt-redrive`, outbox relay'ler | Düşük (#104 `install-relay.sh`, `validate-compose-integration.sh`) |
| **WS-F: Gizlilik/erasure** | P1-7, P2-5, P2-6 | 6 servisin `AccountErasureHandler`'ı, parking retention, waitlist rıza | Kavramsal (#102) — tasarım koordinasyonu |
| **WS-G: Ön yüz UX/i18n/a11y** | P2-7, P2-8, P2-9, P2-17, F-35 (web), F-39 | `web/marketing`, `frontend/apps/web` | Yok (#93 yalnızca stil — aynı dosyalarda hafif çakışma) |
| **WS-H: Auth sertleştirme** | P2-2, P2-3, P2-4 (header), F-36 | `services/auth-service` | Yok |

**Hemen paralel başlatılabilecek iki bağımsız iş:** WS-A (P0-1: gateway waitlist admin filtresi) ve WS-C'nin P1-2 alt işi (deploy guard düzeltmesi + CI testi). İkisi farklı dizinlere dokunur, #104 ile çakışmaz ve her biri küçük/testlenebilir. WS-B (P0-2) en az bunlar kadar acildir ancak #104 sahibiyle sıralama gerektirdiğinden “bağımsız” sayılmadı.
