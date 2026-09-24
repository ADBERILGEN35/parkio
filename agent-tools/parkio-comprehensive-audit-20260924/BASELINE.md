# BASELINE — Parkio kapsamlı denetim (2026-09-24)

> Bu belge, ayrıntılı değerlendirmeye başlamadan önce sabitlenen denetim temel çizgisidir.
> Denetim boyunca temel çizgi **sessizce güncellenmemiştir**.

## 1. Kimlik ve zaman

| Alan | Değer |
|---|---|
| Denetim başlangıcı (UTC) | 2026-09-24T07:05Z |
| Denetlenen kaynak (birincil temel çizgi) | `origin/api` = **`aa865a255564464bed207a9061244af2641edd3d`** (Merge PR #97 `fix/backup-fail-closed-complete`, 2026-09-23 22:00:53 +0300) |
| Varsayılan dal | `master` = `440a8d285a9924f5749918b3993a829e5cf72f07` (Merge PR #98) |
| master ↔ api ayrışması | api, master'ın **498 commit önünde**; master, api'nin **4 commit önünde** (yalnızca `scheduled-restore-drills.yml` + `backup-restore-drill.yml` zamanlama değişikliği) |
| Entegrasyon/sürüm dalı | `api` — PR #44 “DRAFT umbrella: api trunk certification” (`api → master`, taslak). Tüm aktif özellik PR'ları `api`'yi hedefliyor. Deploy workflow'ları ve pin dosyaları `api` içinde. |
| Denetim worktree'si | `/home/user/parkio-audit` — `git worktree add --detach … aa865a25` (başka worktree/dal değiştirilmedi) |
| Rapor dalı | `claude/confident-einstein-68vbka` (yalnızca bu denetim dizini eklendi; ürün kodu değişmedi) |

## 2. Depo düzeni ve araç zinciri

- Gradle 8.11.1 çoklu modül, Java 21 toolchain, Spring Boot 3.5.x (BOM override: Tomcat 10.1.59, Netty 4.1.137, pgjdbc 42.7.12), Spring Cloud 2025.0.3.
- Backend modülleri (`settings.gradle.kts`): `platform:parkio-platform`, 10 servis (`gateway, auth, user, parking, media, gamification, notification, moderation, ai-validation, analytics`), `tools:dlt-redrive`.
  - Boyut (main/test Java dosyası): parking 872/277, auth 181/40, user 166/24, notification 108/23, gamification 103/12, moderation 88/12, ai-validation 85/24, gateway 83/39, media 82/17, analytics 75/12.
- Frontend: pnpm@11.18.0 monorepo (`frontend/`): `apps/web` (SPA + admin), `apps/mobile` (legacy, CI'da “advisory”), `apps/mobile-v2` (Expo), paketler `api-client, config, geo, product-analytics, types, ui, validation`.
- Pazarlama sitesi: `web/marketing` (statik HTML/JS, Hostinger; waitlist formu gateway'e gönderir).
- Operasyon araçları: `scripts/` (bash + Python: backup/restore/deploy/slack_biz relay/New Relic pilot), `docker/` (27 compose dosyası/overlay), `infra/` (terraform, azure, systemd).
- Servis Dockerfile'ları: `eclipse-temurin:21-jdk` → `eclipse-temurin:21-jre` (**tag, digest değil**). Web: `node:22-alpine3.24@sha256:…` (digest) → `nginx:1.30.5-alpine3.24` (tag).

## 3. Konuşlandırılabilir bileşenler ve depo-yapılandırmalı üretim pinleri

Kanonik üretim compose seti: `docker/compose.production.files` → `scripts/parkio-prod-compose.sh`
(`docker-compose.yml`, `apps.yml`, `hosted-beta.yml`, `azure-hosted-beta.yml`, `gmp-release-pins.yml`, `auth-registration-env.yml`, `auth-release-pin.yml`, `web-release-pin.yml`).

| Bileşen | Depodaki pin | Kaynak revizyon (depodaki iddia) | api HEAD'e göre | Not |
|---|---|---|---|---|
| gateway-service | `ghcr.io/…/gateway-service@sha256:7458e4fb…` (registry **index** digest) | `031d4834` (amd64 manifest `cf763ccb…`) | 64 commit geride; `services/` altında yalnızca 1 test dosyası farklı | Kaynak/imaj kimliği depoda belgelenmiş |
| auth-service | `…/auth-service@sha256:a4410a45…` | “Runtime source tip” `b10c1f7c` | 96 commit geride; `services/auth-service` altında yalnızca test farkı | |
| web | `…/web@sha256:aacf9dc9…` (**amd64 platform manifest**) | `3bb89c6c`; config ID `de405e58…`; index `21b54c5b…` | 30 commit geride; `services/`+`frontend/` farkı yok | “8d9bfca4'ü pinleme; CI sentetik MapTiler anahtarını içeriyor” notu var |
| parking-service | `…/parking-service@sha256:02553cad…` | **Belgelenmemiş** (“current live production values”) | Bilinmiyor | Kaynak kanıtı eksik |
| media-service | `…/media-service@sha256:62f49d04…` | **Belgelenmemiş** | Bilinmiyor | Kaynak kanıtı eksik |
| user, gamification, notification, moderation, ai-validation, analytics | **Pin yok** — `apps.yml` içinde `build: context: ..` | Host üzerindeki checkout/yerel imaj | Bilinmiyor | Üretim imaj kökeni depodan belirlenemez |
| Postgres/Redis/Kafka/ClamAV/izleme | Çoğu tag (`postgis/postgis:16-3.4`, `redis:7-alpine`, `confluentinc/cp-kafka:7.7.1`, `clamav/clamav:1.4` …); MinIO/mc digest | — | — | |

Kimlik ayrımı: pin dosyaları bazen registry **index** digest'i (gateway), bazen **platform manifest** digest'i (web) kullanıyor; config ID ayrıca kaydedilmiş (web). Bu, karışıklığa açık ama belgelenmiş.

## 4. Açık işler envanteri (salt okunur; dokunulmadı)

| PR | Durum | Hedef | Konu | Denetimdeki ele alınışı |
|---|---|---|---|---|
| #104 | taslak, `blocked` | api | operasyonel durum, erasure reddi, NR kurtarma bekletmesi (#101/#102/#103'ün ata-tabanlı entegrasyonu). Açıklamada head `e7a29c73`, gerçek head **`db88369c`** (denetim sırasında 07:00Z'de güncellendi). 50 dosya, +6767 | **Bekleyen**; çözülmüş sayılmadı |
| #103 | taslak | api | Kurtarma modunda mevcut tükenmiş NR bütçe defteri şartı | Bekleyen |
| #102 | taslak | api | Host kaybı sonrası isteğe bağlı off-host erasure kurtarma | Bekleyen |
| #101 | taslak | api | Slack/NR operasyonel durum snapshot araçları | Bekleyen |
| #93 | taslak | api | Pazarlama waitlist ad/e-posta alan stili | Bekleyen |
| #56 | taslak | api | Release artifact CI kabulü | Bekleyen |
| #48 | taslak | api | MinIO server/mc digest güncellemesi (CVE azaltımı) | Bekleyen |
| #44 | taslak | master | api gövde sertifikasyonu (şemsiye) | CI sonuçları api HEAD kanıtı olarak kullanıldı |
| #1–#41 (Dependabot, 20 adet) | açık | **master** | temurin 24, jjwt 0.13, kafka-clients 4.3.1, bcprov 1.85, actions v5/v7 … | Bayat dala hedefli (bkz. FINDINGS) |

## 5. api HEAD (`aa865a25`) CI gözlemi — 2026-09-23T19:00–19:32Z (PR #44 check-runs, 42 adet)

- BAŞARILI (örnekler): Build & unit tests, Integration tests (Testcontainers), Typecheck/lint/test/build (web), Mobile-v2, Full Docker Compose runtime validation, k6 smoke, Backup→restore→assert, Encrypted stamp→isolated restore→parity+erasure, Compose dependency recovery drill, Secret scan, Trivy, container scan ×10, CodeQL (java-kotlin), CodeQL (javascript-typescript).
- **BAŞARISIZ**: `CodeQL` toplu kontrolü — “4 new alerts including 4 high severity” (annotasyonlar bu oturumda okunamadı → doğrulama boşluğu); `slack_biz relay acceptance (mock Slack)` — `validate-compose-integration.sh: line 32: …/base/docker/compose.production.files: No such file or directory`; `Legacy mobile … (advisory)`.
- ATLANDI: Deploy invite-production, Rollback, Migrate legacy workspace bind mounts, Non-deploy production runner acceptance, Restore drill + evidence bundle.

## 6. Sonuç kategorileri

- **A. Birleşmiş kaynak** — `aa865a25` (bu denetimin ana konusu).
- **B. Açık/taslak değişiklikler** — yukarıdaki PR'lar; yalnızca çakışma/örtüşme için okundu.
- **C. Depo-yapılandırmalı sürüm artifact'ları** — §3 pinleri. Registry'ye erişilmedi; digest ↔ kaynak eşleşmesi yalnızca depo yorumlarına dayanıyor (iddia).
- **D. Gözlenen dağıtım durumu** — **Yok.** Üretim erişimi yetkili değil; canlı durum gözlenmedi.
- **E. Bilinmeyen canlı durum** — Canlı sağlık, gerçek yedeklerin başarısı, gerçek Slack/NR/e-posta teslimi, host'taki `.env` ve yerel imajlar, branch-protection kuralları (API ile okunamadı).

## 7. Kapsam dışı / kısıtlar

- Üretim SSH/bulut, kimlikli üretim testi, tarama/yük/exploit, gerçek bildirim, gerçek kullanıcı: **yapılmadı** (yetki yok).
- Secret değerleri, özel anahtarlar, gerçek yedek içerikleri, kişisel veri: **okunmadı / yazdırılmadı**.
- Ürün kodu değiştirilmedi; başka dal/worktree/PR'a yazılmadı; PR yorumu/issue açılmadı.
- GitHub branch protection ve code-scanning uyarı detayları mevcut araçlarla okunamadı (doğrulama boşluğu).
