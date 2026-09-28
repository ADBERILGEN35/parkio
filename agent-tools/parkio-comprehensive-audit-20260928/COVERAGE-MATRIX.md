# COVERAGE-MATRIX: Parkio yeniden denetimi (`2877ec81`)

## Yöntem ve sonuç kodları

**Yöntem kısaltmaları:**

| Kısaltma | Anlamı |
|---|---|
| **S** | Statik okuma |
| **D** | `git diff aa865a25 2877ec81` farkının incelenmesi |
| **U** | Depodaki testin çalıştırılması |
| **R** | Hedefli yerel yeniden üretim |
| **CI** | GitHub Actions kayıtları |
| **CQ** | Yerel CodeQL taraması |
| **→** | 2026-09-24 kanıtı taşındı (kod değişmediği doğrulandı) |

**Sonuç kodları:** PASS / FAIL / BLOCKED / NOT RUN / N/A.

**Çalışma biçimi:**
- Bu yeniden denetim tek denetçi tarafından yapıldı; paralel çalışan kullanılmadı.
- Değişen dosyalar tam okundu.
- Değişmeyen alanlar için amaçsız tekrar koşusu yapılmadı.

## 1. Bileşen kapsamı

| Bileşen | Bu denetimde incelenen | Yöntem | Sonuç | İncelenmeyen / sınırlar |
|---|---|---|---|---|
| gateway-service | `WaitlistAdminSecurityWebFilter`, `SessionEpochVerifier`, `AccountStatusVerifier`, yeni entegrasyon testi | D, S, R | F-01 **düzeldi** (R: PASS). Pin ile kaynak arasında fark yok | Canlı Caddy davranışı |
| auth-service | Kod değişmedi. CodeQL Java sonucu değerlendirildi | D, CQ, → | F-13 (auth kısmı), F-14–F-16, F-35, F-36 açık. CSRF uyarısı telafi edici kontrolle kapalı | Unit testler çalıştırılmadı |
| parking-service | `IsparkOccupancyPublicationPolicy`, sorgu servisleri, repository | D, S | F-22 kısmen düzeldi | Yeni testler çalıştırılmadı (CI'da PASS) |
| user, media, moderation, gamification, ai-validation, analytics, notification | Kod değişmedi (media'da yalnızca test değişti) | D, CQ, → | Önceki bulgular açık | — |
| slack_biz relay | Değişmedi. Zehirli yanıt senaryosu yeniden çalıştırıldı | D, R | F-09 **FAIL** (yeniden üretildi) | `run-e2e.sh` BLOCKED (Docker yok) |
| Yedekleme | `backup-hosted-beta.sh`, `backup-databases.sh`, `backup-common.sh`, `backup-minio.sh` | D, S, U | F-02 **düzeldi**. `test-backup-complete-gate.sh` **40/40 PASS** | Gerçek MinIO/Postgres (yetki yok) |
| Geri yükleme | `restore-hosted-beta.sh`, `restore-database.sh`, `restore-safe-preflight.sh`, `restore-erasure-ledger.py`, runbook | D, S, U, R | Preflight **25/25 PASS**. **N-01** (R: bayrak koruması aşıyor). **N-02** (runbook adımları reddediliyor) | Drill-01 betik iç yapısı. Gerçek damga |
| Web deploy guard | `guard-web-synthetic-map-deploy.sh`, `lib/web-map-guard.sh`, `web_bundle_map_config.py`, `parkio-prod-compose.sh` | D, S, U | Test **116/116 PASS** (Part A). F-05R | Part B/C BLOCKED (Docker yok; CI'da zorunlu) |
| Compose / pinler | `compose.production.files`, pin dosyaları, `docker-compose.yml` MinIO değişikliği | D, S | F-11 kısmen. N-03 | Registry içeriği, canlı host |
| CI/CD | 22 workflow farkı, master dağıtıcısı, Dependabot, schedule ve dispatch koşu geçmişi | D, S, CI | F-07R. F-26 büyük ölçüde düzeldi. F-26R. F-40 | Branch protection, repo `vars` |
| CodeQL | JS/TS (1141 dosya + 25 Actions dosyası) ve Java/Kotlin (2242/2362 dosya), varsayılan code-scanning setleri | CQ | 6 high, hepsi yanlış pozitif veya telafi edici kontrollü | GitHub'daki 4 “new” uyarıyla birebir eşleşme |
| Web SPA / pazarlama / mobil | Ürün kodu değişmedi (yalnızca e2e ve mobile paket sürümleri) | D, → | F-19–F-21, F-30, F-39 açık | Tarayıcı/axe yeniden çalıştırılmadı (kod aynı) |
| Monitoring | Alertmanager profili | D, S | F-04 açık | Canlı durum |

## 2. Önemli test sonuçları (2026-09-28)

| Test | Soru | Sonuç |
|---|---|---|
| `AuditHeadBypassReproTest` (uyarlanmış) | Kimliksiz herhangi bir yöntem export'a ulaşır mı? | **PASS**: 6 yöntem 401, preflight 403, export çağrılmadı |
| `scripts/test-backup-complete-gate.sh` | Başarısız aşama olduğunda COMPLETE ve offsite engelleniyor mu? Budama güvenli mi? | **PASS** 40/40 |
| `scripts/test-restore-safe-preflight.sh` | Güvensiz restore girdileri decrypt öncesi reddediliyor mu? | **PASS** 25/25 |
| `scripts/audit-f03-isolated-flag.sh` (denetim harness'ı) | Üretim reddi CLI bayrağıyla aşılabiliyor mu? | **FAIL (kontrol)**: aşılıyor. Bayraksız exit 3, bayrakla exit 0 ve 11 psql uygulaması |
| `scripts/test-guard-web-synthetic-map-deploy.sh` | Guard çalışıyor mu? | **PASS** 116/116. Part B/C BLOCKED (Docker yok) |
| `poison_probe.py` / `poison_worker.py` | Worker crash-loop'u sürüyor mu? | **FAIL (kontrol)**: sürüyor |
| CodeQL JS/TS | 4 high uyarı nedir? | 5 high, hepsi yanlış pozitif |
| CodeQL Java/Kotlin | Aynı soru | 1 high (CSRF disabled), telafi edici kontrollü |
| CI api HEAD | Kırmızı kontrol var mı? | Yalnızca CodeQL toplu kontrolü (GitHub tarafında triage edilmemiş) |
| Zamanlanmış dispatch | api'de gerçekten koşu başlatılıyor mu? | PASS (09-26 ve 09-27 `backend-integration` api success) |
| master schedule | Bayat koşular hâlâ çalışıyor mu? | Çalışıyor ve başarısız (security-ci 5 hafta, backend-integration 3 gece) |

## 3. Tarihsel risklerin güncel durumu

| Tarihsel risk | `2877ec81` |
|---|---|
| Sentetik harita anahtarı üretim build'inde | Guard bundle'ı doğruluyor. Yalnızca bilinen değerler yakalanıyor (F-05R) |
| Dilin URL yerine tarayıcı durumundan alınması | Reset/login sayfalarında sürüyor (F-21; kod değişmedi) |
| Yanıltıcı public resend/waitlist metni | Waitlist 503 metni sürüyor (F-19) |
| UUID bağlama nedeniyle eksik isimler | Düzeltilmiş (değişmedi) |
| Config doğrulamasını geçip çalıştırmada başarısız olan şablonlar | Adreslenmiş (değişmedi) |
| Ledger/DB hatasına rağmen COMPLETE | **Tamamen düzeltildi** (MinIO dahil) |
| Zamanlanmış workflow'ların bayat dalı çalıştırması | api dispatch'i düzeltildi. Bayat kopyalar hâlâ çalışıyor ve kırmızı (F-07R) |
| Operasyonel durum kurtarmasının NR bütçesini sıfırlaması veya Slack'i yeniden oynatması | api'de sürüyor (F-25). #104 HOLD |
| Yalnızca zaman damgasına dayalı erasure kapsamı iddiaları | Supplemental zaman damgası artık kapsam “sertifikalamıyor” (test PASS). Üretimde doğrulanmış kapsam yolu yok (N-02) |

## 4. Tamamlanmayan kapsam (denetim kapsamlı değildir)

- **Canlı dağıtım durumu:** D kategorisi tamamen boş.
- **GitHub yapılandırması:** Branch protection kuralları, repo değişkenleri ve code-scanning uyarı kimlikleri okunamadı.
- **Docker gerektiren testler:** Web guard Part B/C, slack_biz e2e ve restore drill-01 çalıştırılamadı.
- **Değişmeyen alanlar:** Tarayıcı/axe, mobil çalışma zamanı ve kimlikli UI rotaları yeniden test edilmedi; kod değişmediği doğrulandı.
- **Taslak PR'lar:** #118/#119 ayrıntılı güvenlik incelemesinden geçirilmedi. Yalnızca kapsamları okundu.
- **Performans ve yük testi:** Yapılmadı.
