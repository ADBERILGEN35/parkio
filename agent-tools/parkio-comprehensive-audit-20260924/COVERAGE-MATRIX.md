# COVERAGE-MATRIX — Parkio denetimi (`aa865a25`)

Yöntem kısaltmaları: **S** = statik kod/config okuma · **U** = mevcut test çalıştırma · **R** = hedefli yerel yeniden üretim (sentetik, izole) · **B** = tarayıcı (Playwright/axe, mock API) · **CI** = GitHub Actions log/check okuma · **—** = incelenmedi.
Sonuç: PASS / FAIL / BLOCKED / NOT RUN / N/A.

Not: İnceleme, baş denetçi + birbiriyle örtüşmeyen alanlara atanmış 5 salt-okur çalışan tarafından yapıldı (auth/gateway; waitlist/bildirim; domain servisleri; frontend/mobil; ops/CI). Yüksek önemdeki tüm bulgular ve F-01 baş denetçi tarafından ayrıca doğrulandı.

## 1. Bileşen kapsamı

| Bileşen | İncelenen | Yöntem | Sonuç / bulgular | İncelenmeyen / doğrulanmamış sınırlar |
|---|---|---|---|---|
| gateway-service (yönlendirme, JWT, filtreler) | Rota tablosu, JwtTokenValidator, JWKS resolver, header temizleme, PathCanonicalization, ClientIpResolver, CORS, actuator filtresi | S, R | F-01 (R: PASS→açık doğrulandı), F-13, F-28, F-37 | Canlı Caddy davranışı; rate limiter'ın Redis kesintisindeki gerçek davranışı (NOT RUN) |
| gateway waitlist | Controller, servis, JDBC repo, CSV, hasher, ops outbox/exporter, V1–V5 | S, R | F-01, F-18, F-33, F-34, F-14(3), F-08 notu | ResendWaitlistEmailSender derinliği; çok replikalı exporter |
| auth-service | Kayıt modları, davetiye, doğrulama, reset, login, refresh rotasyonu, logout, admin, erasure, JWT/RSA | S | F-13, F-14, F-15, F-16, F-35, F-36 | Unit testler çalıştırılmadı (ilk denemede Gradle önbelleği yoktu; daha sonra ağ çalıştı ama kapsam dışı bırakıldı); JVM locale; zamanlama ölçümü |
| user-service | Profil güncelleme (mass assignment), yer/favori sahipliği, public profil | S | PASS (IDOR/mass assignment bulunmadı); bilgi: public profil moderasyon durumu gösteriyor | Smart-return iç uç noktası (önceki #7) yeniden doğrulanmadı |
| parking-service | Public explore, geocoding, PostGIS sorguları, belediye senkronu, İZUM guard, oturumlar, spotlar, idempotency, erasure, migration'lar | S | F-10, F-17, F-22, F-23, F-38; PASS: SQL param, SRID/birim, IDOR yok, kısmi unique index | Konya/Kayseri/ANPARK normalizer'ları, OSM/İZELMAN parser'ları derinlemesine; ranking/fraud tablolarının saklama süresi; testler NOT RUN |
| media-service | Yükleme sınırları, MIME/magic byte, ClamAV, piksel sınırı, EXIF, anahtar üretimi, presigned URL | S | F-38 (checksum, minioadmin); PASS genel | Görüntü çözme bellek davranışı çalıştırılmadı |
| moderation / gamification / ai-validation / analytics | Yetki, rapor akışı, puan akışı, sınıflandırıcı varsayılanı, aggregate güncelleme | S | F-31, F-38(analytics), F-10 | Gemini prompt injection; testler NOT RUN |
| notification-service | Tüketici transaction sınırları, outbox relay, DLT | S | F-24, F-32 | Push teslimi (önceki denetimde no-op) yeniden doğrulanmadı; cihaz token yeniden atama (önceki #14) doğrulanmadı |
| platform/parkio-platform | Header sabitleri | S (kısmi) | — | Kalanı |
| Kafka / Redis / MinIO / ClamAV | Compose config, protokol, portlar | S | F-16, F-29 | ACL/ağ politikası canlıda; broker yapılandırması |
| slack_biz relay | transport, delivery, worker, store, inbox, adapters, templates, safety, systemd unit'leri | S, U, R | U: W01–W24 24/24 PASS, Y03 15/15 PASS, Y03A 13 PASS + 1 NOT_EXECUTED; R: F-09 doğrulandı; `run_named_export_chain.py` BLOCKED (gateway çıktısı gerekiyor) | `waitlist-e2e/run-e2e.sh`, Civo systemd kontrolü BLOCKED (Docker imajı yok) |
| NR log pilotu | budget_gate, test kontrol bayrakları, overlay'ler | S | F-25 | fluent-bit/NR config derinliği |
| Alertmanager / Prometheus | Profil durumu, hedefler, backup kural grubu, şablon render testi varlığı | S, config render | F-04; `test-alertmanager-slack-render.sh` BLOCKED (imaj yok) | Tüm kural grupları, blackbox hedefleri, Grafana/Loki/Tempo |
| Yedekleme / geri yükleme | backup-hosted-beta, backup-databases, backup-minio, backup-common, erasure-tombstones, restore-hosted-beta, runbook | S, U, R | U: `test-backup-production-fail-closed.sh` 17/17 PASS, `test_backup_metrics.py` 8 OK, `test_restore_readiness.py` 49 OK; R: F-02 (h1, h2) doğrulandı; F-03 S | Restore drill betiklerinin iç yapısı; gerçek damga geri yükleme (yetki yok) |
| Deploy / rollback / pin'ler | compose.production.files, pin dosyaları, parkio-prod-compose, deploy-common, guard, deploy workflow'ları | S, R, config render | F-05 (R), F-06, F-11, F-12 | Host üzerindeki dosyalar, `.env`, runner durumu (E) |
| CI/CD | 24 workflow, dependabot, izinler, tetikleyiciler, zamanlamalar | S, CI | F-07, F-08, F-26, F-40; PASS: `pull_request_target` yok, üst düzey `permissions` her yerde | Branch protection, repo `vars`, ortam onaylayıcıları (API ile okunamadı) |
| infra (terraform/azure/systemd) | Yalnızca referans verilen parçalar | S (kısmi) | — | Terraform modülleri, NSG kuralları derinlemesine |
| Web SPA | Token saklama, redirect sanitizasyonu, XSS sink'leri, i18n anahtar eşitliği, auth akışları, explore, harita config, SW, source map, bundle | S, U, B | vitest PASS; axe: auth sayfaları 0 ihlal; F-19..F-21, F-35, F-39 | Kimlikli rotalar (`/map`, profil, yükleme, moderasyon, admin) axe/tarayıcı ile NOT RUN |
| Pazarlama sitesi | waitlist.js, i18n.js, sayfalar, .htaccess | S, U, B | `node --test` 17/17 PASS; axe 7 sayfa; F-18, F-19, F-30, F-39 | Hostinger'ın gerçek başlıkları |
| Mobil (mobile-v2, legacy) | Token depolama, deep link, WebView, CI logları | S, CI | Legacy advisory FAIL (expo-doctor sürüm sapması); F-26 | Cihaz/simülatör çalıştırma NOT RUN; mobil jest yerelde NOT RUN (CI PASS) |
| Depoya işlenmiş artifact'lar | dist/, zip, backup-artifacts, deploy-artifacts, tmp, test-results, .env* | S, desen taraması | Gerçek veri/sır bulunmadı; tracked `.env*` yalnızca `.example`; `valid.env` PEM yer tutucusu 145 bayt | Git geçmişinin tam sır taraması (yalnızca HEAD ağacı tarandı) |

## 2. Parkio'ya özgü uçtan uca akışlar

| Akış | Kapsam | Sonuç |
|---|---|---|
| Waitlist: ad zorunlu, e-posta, rıza, TR/EN, onay, çıkış, yeniden abonelik | S + U + B | Çalışıyor; F-18 (rıza kanıtı), F-19 (503 mesajı), F-33 (yarış). Çıkış → e-posta/hash pseudonimleştirme, yeniden abonelik yeni PENDING satır — PASS |
| Admin waitlist: erişim, isimler, eski null kayıtlar, sayfalama, CSV | S + R | F-01, F-13, F-34; null isim gösterimi tutarlı — PASS |
| Bildirim zinciri: DB → outbox → export zarfı → consumer → relay kuyruğu → mock Slack | S + U | Atomiklik PASS (onay + outbox aynı işlem, dedup anahtarı unique). Not: ops outbox yazma hatası NESTED savepoint ile yutulup yalnızca `record_failed` metriği artıyor (`JdbcWaitlistOpsNotificationOutbox.java:74-96`) — bilinçli tasarım; bu metrik için uyarı kuralı önerilir (REMEDIATION P3). Relay: F-09 |
| Bildirim gizliliği, sanitizasyon, dedup, sayım anlık görüntüsü | S + U | Mention nötrleştirme PASS (W22), dedup PASS, sayımlar “itibarıyla” etiketli PASS; F-39 benzeri çıplak alan adı otomatik link (Düşük, FINDINGS'e ayrıca alınmadı — Slack metin sertleştirme önerisi REMEDIATION'da) |
| Kayıt CLOSED/INVITE, davetiye süresi, dil URL'leri | S | Sunucu tarafı zorlama PASS; F-14 (INVITE enumeration) |
| Doğrulama/yeniden gönderim/cooldown/reset/login | S + B | Enumeration-güvenli metin PASS; F-14, F-15, F-21, F-35, F-36 |
| Profil tamamlama ve telefon verisi | S (kısmi) | `fix/pending-profile-no-phone-storage` dalı mevcut; api'ye dâhil olup olmadığı doğrulanmadı — boşluk |
| Public Explore, belediye işaretleri, detaylar | S + B | Parametre allow-list, sınırlar PASS; F-20, F-22 |
| Yol kenarı ve belediye keşif sınırları, kaynak filtreleri, mesafe | S | PASS (yol kenarı lat/lng doğrulanıyor); `/spots/nearby` F-38 |
| Stale/live/unknown doluluk | S | Sunucu null'lıyor PASS; “LIVE” semantiği F-22 |
| Harita sağlayıcı config, gerçek vs sentetik build | S + R + B | Build-time boş anahtar kontrolü PASS; F-05, F-20. Gerçek MapTiler ile kabul: NOT RUN (mock harita sağlayıcı kabulü değildir) |
| Smart-return ve diğer bayraklı özellikler | S (kısmi) | `PARKIO_SMART_RETURN_ENABLED` varsayılan false; derin inceleme yok |
| Topluluk raporlama, moderasyon | S | F-31 |
| Mobil eşdeğerlik | S + CI | Token güvenli depolama PASS; akış eşdeğerliği incelenmedi |

## 3. Tarihsel risklerin bu revizyondaki durumu

| Tarihsel risk | Durum `aa865a25` |
|---|---|
| Sentetik harita anahtarının üretim build'ine girmesi | Pin düzeltilmiş (web `3bb89c6c`); **guard etkisiz** → F-05 |
| Dilin URL niyeti yerine kalıcı tarayıcı durumundan alınması | Register/verify ve pazarlama düzeltilmiş; **reset/login'de sürüyor** → F-21 |
| Yanıltıcı public resend başarı metni | Auth resend metni enumeration-güvenli ve dürüst — düzeltilmiş; **waitlist 503 metni yanıltıcı** → F-19 |
| UUID bağlama nedeniyle export'ta eksik isimler | **Düzeltilmiş** (`JdbcWaitlistInterestRepository.java:259-264`, özel test); regresyon sessiz kalır (exporter hataları “Ad belirtilmemiş” olarak maskeliyor — Düşük) |
| Config doğrulamasını geçip çalıştırmada başarısız olan şablonlar | **Adreslenmiş** — Alertmanager şablonları gerçek imajda render testi (`test-alertmanager-slack-render.sh`, `observability-validation.yml`); bu denetimde çalıştırılamadı (BLOCKED) |
| Ledger/DB export hatasına rağmen backup COMPLETE | DB/ledger için **düzeltilmiş** (5414cf71); **MinIO ve tekil betik için sürüyor** → F-02 |
| Zamanlanmış workflow'ların bayat dalı çalıştırması | Restore drill'leri için düzeltilmiş; **diğer 7 zamanlanmış workflow için sürüyor** → F-07 |
| Operasyonel durum kurtarmasının NR bütçesini sıfırlaması / Slack'i yeniden oynatması | **api'de sürüyor** → F-25; düzeltme PR #101–#104'te **bekliyor** |
| Yalnızca zaman damgasına dayalı erasure kapsama iddiaları | **Sürüyor** — drill-01 “supplemental covered-through” operatör beyanı; DR restore yolu yeni ledger'ları uygulamıyor → F-03 |

## 4. Önemli test sonuçları

| Test | Soru | Sonuç |
|---|---|---|
| `AuditHeadBypassReproTest` (izole kopya) | Anonim HEAD admin export'a ulaşır mı? | **FAIL (güvenlik kontrolü)** — ulaşıyor; GET 401 |
| `poison_probe.py` / `poison_worker.py` | Bozuk Slack yanıtı worker'ı çökertir mi, deneme sınırı uygulanır mı? | **FAIL** — çöküyor, 3 gönderim, attempts=0 |
| Ops h1 harness | MinIO hatasında COMPLETE engellenir mi? | **FAIL** |
| Ops h2 harness | Tekil DB yedeği hatada COMPLETE engeller mi? | **FAIL** |
| `test-guard-web-synthetic-map-deploy.sh` | Guard çalışır mı? | **FAIL** 5/5 (Permission denied) |
| Guard varyant tablosu | Guard sentetik anahtar varyantlarını yakalar mı? | **FAIL** 1/8 yakalandı |
| `validate-compose-integration.sh origin/master` | CI kırmızısı yerelde üretilir mi? | Üretildi (ortam/base-ref sorunu) |
| `test-backup-production-fail-closed.sh` | Mevcut fail-closed testi | PASS 17/17 (ama F-02'yi kapsamıyor) |
| `test_backup_metrics.py`, `test_restore_readiness.py` | Yardımcı mantık | PASS 8, PASS 49 |
| slack_biz kabul (W, Y03, Y03A) | Relay mock Slack ile çalışır mı? | PASS (1 NOT_EXECUTED: canlı Kafka tüketici) |
| `node --test web/marketing/*.test.mjs` | Pazarlama adaptör/i18n testleri | PASS 17/17 |
| web vitest | SPA birim testleri | PASS (878/879 + PNG geri konunca 3/3) |
| axe (auth sayfaları, explore, pazarlama) | WCAG 2.2 AA otomatik kontroller | Auth PASS (0); explore: region (moderate) + target-size (serious); pazarlama: color-contrast FAIL |
| Backend unit/integration (tam) | — | NOT RUN (amaçsız geniş koşu yapılmadı; CI'da api HEAD için PASS görüldü) |
| Gerçek sağlayıcılar (Slack, NR, Resend, MapTiler), canlı üretim | — | NOT APPLICABLE / yetki yok |

## 5. Tamamlanmayan kapsam (denetim kapsamlı değildir)

- Branch protection, repo değişkenleri, code scanning uyarı içerikleri (F-08).
- Canlı dağıtım durumu (D kategorisi tamamen boş).
- Kimlikli web rotaları ve mobil uygulamaların çalışma zamanı UX/erişilebilirlik testi; ekran okuyucu testi.
- Terraform/NSG, Grafana/Loki/Tempo, tüm Prometheus kuralları.
- Önceki denetimin 3, 8, 10, 14, 17–19, 21–23 numaralı bulgularının yeniden doğrulanması.
- Performans: k6 smoke CI'da PASS görüldü; yük/ölçek testi yapılmadı; N+1 ve bellek bulguları statik.
- Git geçmişinde tam sır taraması (yalnızca HEAD ağacı desen taraması; CI gitleaks işi api HEAD'de PASS).
