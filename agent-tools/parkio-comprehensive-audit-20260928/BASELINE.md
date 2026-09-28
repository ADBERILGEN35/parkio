# BASELINE: Parkio kapsamlı yeniden denetim (2026-09-28)

> Bu belge, ayrıntılı değerlendirmeden önce sabitlenen temel çizgidir.
> Önceki denetim (`../parkio-comprehensive-audit-20260924/`, temel çizgi `aa865a25`) **değiştirilmedi**. O denetimin sonuçları bu belgede yalnızca karşılaştırma için kullanılıyor.
> Denetim süresince temel çizgi sessizce güncellenmedi.

## 1. Kimlik ve zaman

| Alan | Değer |
|---|---|
| Denetim başlangıcı (UTC) | 2026-09-28T07:18Z |
| Denetlenen kaynak (birincil temel çizgi) | `origin/api` = **`2877ec81df17d76311125592dfcf66c4b8f6d324`** (Merge PR #109 `fix/restore-safe-preflight`, 2026-09-27 22:59:33 +0300) |
| Önceki temel çizgi | `aa865a25` (2026-09-24). Aradaki fark: **53 commit**, 146 dosya, +10.747 / −1.856 satır |
| Varsayılan dal | `master` = `82398c25` (Merge PR #117 `ci/schedule-dispatch-api`) |
| master ↔ api | api, master'ın 551 commit önünde. master, api'nin 7 commit önünde; bunların hepsi zamanlanmış dağıtıcı (`scheduled-restore-drills.yml`) değişikliği. |
| Entegrasyon/sürüm dalı | `api`. PR #44 (`api → master`, taslak şemsiye) CI kanıtı olarak kullanıldı. |
| Denetim worktree'si | `/home/user/parkio-audit-0928`: `git worktree add --detach … 2877ec81`. Başka worktree veya dal değiştirilmedi. |
| Dinamik testlerin çalıştığı yer | Oturum scratch dizinindeki `git archive` kopyaları. Ürün worktree'sine yazılmadı. |

## 2. Temel çizgiler arası birleşmiş değişiklikler (önceki bulgulara göre)

| PR | Konu | İlgili önceki bulgu |
|---|---|---|
| #106 (+ #107 gateway pin) | Waitlist admin yetkisi tüm HTTP yöntemleri için uygulanıyor; epoch ve hesap durumu kontrolü eklendi | F-01, F-13 (waitlist kısmı) |
| #105 | MinIO veya gerekli bir aşama başarısızsa COMPLETE reddediliyor; budama başarıya bağlandı | F-02 |
| #108, #110 | Web harita deploy guard'ı yeniden yazıldı: model render, imaj doğrulama, digest bağlama | F-05 |
| #109 | Güvensiz üretim restore girdileri decrypt öncesi reddediliyor; üretim restore'u **tamamen engellendi** | F-03 |
| #111 (+ #112 parking pin) | Kapalı İSPARK otoparkları için doluluk yayımlanmıyor | F-22 (kısmen) |
| #116 | GHA güvenilirliği, Expo 56.0.22, MinIO imajları özel GHCR'ye taşındı | F-26 (kısmen) |
| #117 (master) | Kalan zamanlanmış workflow'lar api'ye dağıtılıyor | F-07 (kısmen) |

## 3. Depo-yapılandırmalı üretim artifact'ları (C kategorisi)

Kanonik set `docker/compose.production.files` değişmedi (8 dosya).

| Bileşen | Pin (digest) | Kaynak revizyon (depodaki iddia) | Pin kaynağı ile HEAD arası fark | Not |
|---|---|---|---|---|
| gateway-service | `…/gateway-service@sha256:866a7fe0…` (tek platform manifest; config `2176c4b3…`) | `f9710aa0` (PR #106) | `services/gateway-service/src/main` farkı **yok** | F-01 düzeltmesini içeriyor |
| parking-service | `…/parking-service@sha256:85da2654…` (config `0f62313a…`) | `1dd2d5bc` (PR #111) | `src/main` farkı **yok** | Kaynak artık belgelenmiş |
| media-service | `…/media-service@sha256:62f49d04…` | **Belgelenmemiş** (“live production values”) | Bilinmiyor | Kaynak kanıtı eksik (değişmedi) |
| auth-service | `…/auth-service@sha256:a4410a45…` | `b10c1f7c` | `src/main` farkı **yok** | |
| web | `…/web@sha256:aacf9dc9…` (amd64 manifest; config `de405e58…`; index `21b54c5b…`) | `3bb89c6c` | `frontend/apps/web/src` ve `packages` farkı **yok** | |
| user, gamification, notification, moderation, ai-validation, analytics | **Pin yok** (`build:`) | Host checkout'u | Bilinmiyor | Değişmedi |
| MinIO / mc (varsayılan) | `ghcr.io/adberilgen35/parkio/{minio,mc}@sha256:…` | Özel GHCR'ye yeniden yayımlanmış; yalnızca linux/amd64 doğrulanmış | — | Özel registry kimlik bilgisi gerektiriyor (yeni bağımlılık) |

## 4. Açık işler (salt okunur incelendi, dokunulmadı)

| PR | Durum | Hedef | Konu | Bu denetimde |
|---|---|---|---|---|
| #119 | taslak, 2026-09-27'de açıldı | #118 dalı | auth: varsayılan kapalı kalıcı erasure kaydı + beklenen sınır kurtarması | Bekliyor; çözüm sayılmadı |
| #118 | taslak | api | İzole kurtarma kanıt sözleşmesi (tasarım). Gövdesinde “F-03 üretimde kapatılmadı” yazıyor | Bekliyor |
| #104 (+ #101/#102/#103) | taslak, HOLD | api | Operasyonel durum, erasure reddi, NR kurtarma. Head `c24f4f3d` | Bekliyor. F-25 hâlâ açık |
| #93, #56, #48 | taslak | api | Stil / release kabulü / MinIO digest | Değişmedi |
| #44 | taslak şemsiye | master | api sertifikasyonu | CI kanıtı |
| Dependabot #1–#41 | açık | **master** | — | Bayat hedef (değişmedi) |

## 5. api HEAD (`2877ec81`) CI gözlemi (2026-09-27T19:59–20:30Z, PR #44 check-run'ları)

- **BAŞARILI:**
  - Build & unit tests, Integration tests (Testcontainers), Typecheck/lint/test/build
  - Mobile-v2 (doctor artık bloklayıcı), Legacy mobile (advisory)
  - Full Docker Compose runtime validation, k6 smoke
  - Backup→restore→assert, Encrypted stamp→isolated restore
  - Compose dependency recovery drill, **slack_biz relay acceptance**
  - Secret scan, Trivy, 10 container scan, CodeQL java-kotlin / javascript-typescript işleri
  - SBOM, Provenance manifest, Fresh-runner GHCR MinIO pull
- **BAŞARISIZ:** `CodeQL` toplu kontrolü: “4 new alerts including 4 high”. Önceki denetimde de aynıydı; triage edildiğine dair kanıt yok.
- **ATLANDI:** deploy/rollback/migrate işleri (tasarım gereği).
- **Zamanlanmış koşular:** master'daki dağıtıcı api'de `backend-integration` koşusunu 2026-09-26 ve 2026-09-27'de başlattı; ikisi de başarılı. Master'daki eski cron'lu kopyalar ise her gece/hafta **başarısız** oluyor (bkz. FINDINGS).

## 6. Sonuç kategorileri

- **A. Birleşmiş kaynak:** `2877ec81`.
- **B. Açık/taslak değişiklikler:** Yukarıdaki PR'lar. Hiçbiri çözüm sayılmadı.
- **C. Depo-yapılandırmalı artifact'lar:** §3. Registry'ye erişilmedi; digest ile kaynak arasındaki eşleşme depo yorumlarına dayanıyor.
- **D. Gözlenen dağıtım durumu:** **Yok** (yetki dışı).
- **E. Bilinmeyen canlı durum:**
  - Canlıda hangi imajların çalıştığı
  - Alertmanager'ın durumu
  - Gerçek yedeklerin sonucu
  - Host'ta GHCR oturumu
  - Slack/NR teslimatı
  - Branch protection kuralları

## 7. Kapsam dışı ve kısıtlar

- Önceki denetimdeki yetki sınırlarının hepsi geçerli: üretime erişim yok, gerçek bildirim yok, sır okuma yok, ürün kodu değişikliği yok, PR yorumu yok.
- CodeQL uyarı listesi GitHub API'den okunamadı. Bu nedenle CodeQL 2.27.1 yerelde aynı dil ve sorgu setleriyle çalıştırıldı (bkz. FINDINGS F-08).
