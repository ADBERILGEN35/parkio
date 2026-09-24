# FINDINGS — Parkio kapsamlı denetim (temel çizgi `aa865a25`, 2026-09-24)

Okuma rehberi
- **Durum**: *Doğrulandı* = kod okunarak ve/veya yerel yeniden üretimle kanıtlandı; *Şüpheli* = kod güçlü biçimde işaret ediyor ama çalıştırılmadı/ön koşul belirsiz; *Doğrulama boşluğu* = kanıt eksik, kusur iddia edilmiyor.
- **Etkilenen temel çizgi**: A = birleşmiş kaynak `aa865a25`; C = depo-yapılandırmalı üretim artifact'ı/compose seti; E = canlı durum bilinmiyor.
- **Üretim ilgisi**: canlı ortam gözlenmedi; hiçbir bulgu için “observed” verilmedi.
- Yollar depo köküne göredir. Servis yolları kısaltmaları: `gw/` = `services/gateway-service/src/main/java/com/parkio/gateway/`, `auth/` = `services/auth-service/src/main/java/com/parkio/auth/`, `parking/` = `services/parking-service/src/main/java/com/parkio/parking/`.
- Önceki denetim (`SECURITY_AND_QUALITY_AUDIT.md`, 2026-07-04) bulguları bu revizyonda yeniden kontrol edildi; düzeltilenler §Z'de listelenmiştir.
- Bulgular kök nedene göre birleştirildi; aynı kök neden puanlamada bir kez sayıldı.

## Özet tablo

| ID | Başlık | Kategori | Önem | Öncelik | Durum |
|---|---|---|---|---|---|
| F-01 | Anonim `HEAD` isteği waitlist admin/export korumasını atlıyor | Güvenlik açığı | Orta | **P0** | Doğrulandı (yeniden üretildi) |
| F-02 | Yedekleme, MinIO aynası başarısız/eksikken `COMPLETE` yazıp offsite'a yüklüyor | Operasyonel/DR kusuru | **Yüksek** | **P0** | Doğrulandı (harness) |
| F-03 | DR geri yükleme yolu bütünlük, `success` ve yeni erasure ledger kontrolü yapmıyor | Operasyonel/DR kusuru | **Yüksek** | P1 | Doğrulandı (kod) |
| F-04 | Kanonik üretim compose setinde Alertmanager (ve Loki/Promtail/Tempo) devre dışı | Operasyonel boşluk | **Yüksek** | **P0** (doğrula) | Doğrulandı (config render) / canlı bilinmiyor |
| F-05 | Sentetik harita anahtarı deploy guard'ı çalıştırılamaz modda ve kolayca atlatılabilir | Sürüm güvenliği kusuru | **Yüksek** | P1 | Doğrulandı (yeniden üretildi) |
| F-06 | CI rollback workflow'ları önceki koşunun manifest artifact'ını indiremiyor | Operasyonel kusur | Orta | P1 | Doğrulandı (kod) |
| F-07 | Zamanlanmış güvenlik/entegrasyon workflow'ları ve Dependabot bayat `master`'ı hedefliyor | CI/CD riski | Orta | P1 | Doğrulandı |
| F-08 | CodeQL, api HEAD'de 4 yeni “high” uyarı raporluyor; içerik/triage bilinmiyor | Doğrulama boşluğu | (bilinmiyor) | P1 | Doğrulama boşluğu |
| F-09 | Slack relay worker tek bir bozuk yanıtla crash-loop'a giriyor; `max_attempts` atlanıyor, sınırsız tekrar gönderim | Veri/olay tutarlılığı | Orta | P1 | Doğrulandı (yeniden üretildi) |
| F-10 | Hesap/erasure ack'i DB işlemi içinde; ~1,5 sn yeniden deneme; tombstone'lar okunmuyor | Veri bütünlüğü / gizlilik | Orta | P1 | Doğrulandı (kod) |
| F-11 | Üretim imaj kökeni: 6 servis host'ta build ediliyor, 2 pin'in kaynağı belgesiz, imza doğrulaması yok | Deploy tekrarlanabilirliği | Orta | P1 | Doğrulandı |
| F-12 | Deploy profilleri arasında sapma (deploy betiği auth/web pinlerini ve kayıt env'ini düşürüyor) | Operasyonel risk | Orta | P1 | Doğrulandı (kod) |
| F-13 | Yetki iptali (rol iptali, admin “tüm oturumları iptal”) mevcut access token'ları geçersiz kılmıyor; waitlist admin filtresi epoch/durum kontrolünü atlıyor | Güvenlik zayıflığı | Orta | P2 | Doğrulandı (kod) |
| F-14 | Hesap varlığı sızıntıları: INVITE modunda 409-önce-davetiye, zamanlama farkı, waitlist 503 kehaneti | Güvenlik zayıflığı | Orta | P2 | Doğrulandı (kod); zamanlama ölçülmedi |
| F-15 | Yalnızca e-postaya bağlı kalıcı hesap kilitleme (hedefli DoS) | Güvenlik zayıflığı | Orta | P2 | Doğrulandı (kod) |
| F-16 | İç güven modeli: tek paylaşılan sır, auth-service admin kararını `X-User-Roles` başlığından alıyor, Kafka PLAINTEXT/ACL'siz | Mimari risk | Orta | P2 | Doğrulandı (kod/config); istismar ön koşullu |
| F-17 | Konum arama/görüntüleme logları süresiz saklanıyor | Gizlilik | Orta | P2 | Doğrulandı (kod) |
| F-18 | Waitlist onay kaydında rıza metni sürümü/açık rıza alanı yok; sunucu rıza istemiyor | Gizlilik/ürün | Orta | P2 | Doğrulandı (kod) |
| F-19 | Pazarlama waitlist formu herhangi bir 503'te “kaydınız alındı” diyor | Yanıltıcı başarı mesajı | Orta | P2 | Doğrulandı (ekran görüntüsü) |
| F-20 | Harita stil/tile hatasında boş harita, mesaj yok; Explore hatasında yeniden deneme yok | UX/işlevsel | Orta | P2 | Doğrulandı (ekran görüntüsü) |
| F-21 | Şifre sıfırlama (ve login) sayfası e-posta linkindeki `?lang=` parametresini yok sayıyor | i18n kusuru | Orta | P2 | Doğrulandı (ekran görüntüsü) |
| F-22 | İZUM/İSPARK doluluk verisi kaynak zamanı yerine çekim zamanıyla “LIVE”; küçülen İZUM beslemesi hiç eskale edilmiyor | İşlevsel doğruluk | Orta | P2 | Doğrulandı (kod) |
| F-23 | Belediye besleme istemcilerinde yanıt boyutu sınırı ve toplam süre sınırı yok | Kullanılabilirlik/DoS | Orta | P2 | Doğrulandı (kod) |
| F-24 | Paylaşılan DLT'nin redrive aracı kayıtları yanlış topic'e gönderip sessizce kaybettirebiliyor | Veri/olay tutarlılığı | Orta | P2 | Doğrulandı (kod) |
| F-25 | NR bütçe ve Slack relay SQLite durumu yedeklenmiyor; kayıp durumda harcama 0'a sıfırlanıyor | DR/operasyon | Orta | P2 | Doğrulandı (kod); PR #101–#104 bekliyor |
| F-26 | CI yanlış kırmızı/yanlış yeşil: slack_biz compose kontrolü, expo-doctor `continue-on-error`, guard testi CI'da yok, fail-closed testi yalnızca grep | Test/CI kalitesi | Orta | P2 | Doğrulandı |
| F-27 | Kurtarma kanıtı tamamen sentetik; RPO/RTO belgeleri çelişkili; drill-01 parity FAIL kaydı | Operasyonel boşluk | Orta | P1 | Doğrulama boşluğu |
| F-28 | Gateway `/actuator/prometheus` herkese açık | Bilgi ifşası | Düşük | P2 | Doğrulandı (config) |
| F-29 | Konteyner sertleştirme boşlukları (docker.sock, cap_drop yok, 0.0.0.0 bind'li doğrulama compose'u, `chmod a+rwX`) | Altyapı | Düşük | P2 | Doğrulandı (config) |
| F-30 | Pazarlama sitesi WCAG 1.4.3 kontrast ihlalleri ve sayfa dili uyumsuzluğu | Erişilebilirlik | Düşük | P2 | Doğrulandı (axe) |
| F-31 | Oyunlaştırma/moderasyon kötüye kullanım kontrolleri (çift hesap puan çiftliği, kendi vakasını karara bağlama, rapor seli) | Ürün/kötüye kullanım | Orta (özellik açıksa) | P3 | Şüpheli |
| F-32 | Bildirim outbox'ında zehirli payload relay'i kilitliyor; bildirim dead-letter kurtarma yolu yok | Veri/olay tutarlılığı | Düşük | P3 | Doğrulandı (kod) |
| F-33 | Waitlist eşzamanlı gönderim/yeniden gönderimde token rotasyonu ilk e-postanın linklerini öldürüyor | Veri tutarlılığı | Düşük | P3 | Şüpheli |
| F-34 | Waitlist admin sayfalama int taşması, sıralama bağı, sınırsız bellek içi CSV, BOM yok | Doğruluk/performans | Düşük | P3 | Doğrulandı (kod) |
| F-35 | Eski e-posta doğrulama token'ları çalışmaya devam ediyor ve hesap verisi döndürüyor; web iki kez çağırıyor | Güvenlik (düşük) | Düşük | P3 | Doğrulandı (kod + tarayıcı) |
| F-36 | 72 bayttan uzun parolalar politikayı geçip hash aşamasında 500 verebilir | Doğruluk | Düşük | P3 | Şüpheli |
| F-37 | Doğrulanmamış `X-Correlation-Id` düz metin loglara ve yanıta yansıtılıyor | Log bütünlüğü | Düşük | P3 | Doğrulandı (kod) |
| F-38 | Küçük domain kusurları: `last_confirmed_at` TIMESTAMP (tz'siz), `/spots/nearby` enlem/boylam kontrolsüz, N+1, global medya checksum, `minioadmin` fallback | Doğruluk/performans | Düşük | P3 | Doğrulandı (kod) |
| F-39 | Tarayıcı/CSP/önbellek hijyeni (pazarlama `unsafe-inline`, nginx CSP gevşek, SW önbelleği büyüyor, URL'de e-posta/token, Unsplash) | Güvenlik sertleştirme | Düşük | P3 | Doğrulandı (kod) |
| F-40 | Actions tag ile pinli; dispatch girdileri `run:` içine enterpole ediliyor | Tedarik zinciri | Düşük | P3 | Doğrulandı (kod) |

Önem sayıları (doğrulanmış/şüpheli bulgular, F-08 doğrulama boşluğu hariç): **Kritik 0 · Yüksek 4 · Orta 23 · Düşük 12** (F-31 ve F-36, F-33 şüpheli).

---

## Ayrıntılı bulgular

### F-01 — Anonim `HEAD` isteği waitlist admin/export korumasını atlıyor
- **Kategori:** Güvenlik açığı — Broken access control (CWE-285; yönteme bağlı kontrol, CWE-650 ile ilişkili)
- **Önem:** Orta · **Öncelik:** P0 (tek satırlık düzeltme, herkese açık uç nokta) · **Güven:** Yüksek · **Durum:** Doğrulandı
- **Etkilenen temel çizgi:** A; C (gateway pin `031d4834` aynı dosyayı içeriyor — `git diff 031d4834 aa865a25 -- services/gateway-service/src/main` boş) · **Bileşen:** gateway-service waitlist yönetimi
- **Dosya/satır:** `gw/infrastructure/security/WaitlistAdminSecurityWebFilter.java:101-105` (`if (method != HttpMethod.GET) return false;`); `gw/presentation/waitlist/WaitlistController.java:91,99,112` (`@GetMapping`); `gw/infrastructure/persistence/waitlist/JdbcWaitlistInterestRepository.java:167-183` (limitsiz export sorgusu)
- **Gözlenen:** `GET /api/v1/waitlist/export` → 401; `HEAD /api/v1/waitlist/export` token olmadan → **200 `text/csv`**, `Content-Disposition: attachment`, export sorgusu çalıştı, JWT doğrulayıcı hiç çağrılmadı.
- **Beklenen:** Korunan yollar yöntemden bağımsız ADMIN/SUPER_ADMIN istemeli; HEAD dâhil tüm yöntemler 401/403 almalı.
- **Kök neden:** Filtre “korunan”ı yönteme göre tanımlıyor; Spring WebFlux `@GetMapping` işleyicilerini HEAD için de eşliyor. Yerel controller'lar gateway `GlobalFilter` zincirinden (rate limiter, epoch/status kontrolü) geçmiyor.
- **Tetikleyici/ön koşul:** API host'una internet erişimi; Caddy HEAD'i iletiyor (Caddyfile'da yöntem kısıtı yok).
- **Etki:** Satır verisi dönmüyor (HEAD gövdesi atılır) — PII doğrudan sızmıyor. Ancak `Content-Length` onaylı kayıt listesinin bayt boyutunu (yaklaşık abone sayısını) sızdırır; kimliksiz, rate-limit'siz, tam tablo export sorgusu tekrar tekrar tetiklenebilir (DB yükü). `/admin` ve `/admin/summary` de aynı şekilde çalışır.
- **Kanıt:** `evidence/repro-gateway-head/` — `AuditHeadBypassReproTest.java` + `RESULT.md` (komut ve çıktı: `AUDIT GET status=401`, `AUDIT HEAD status=200 OK content-type=text/csv`).
- **Mevcut azaltım:** Yok (HEAD için).
- **Önerilen asgari düzeltme:** `isProtected` yol eşleşmesine göre karar versin (yöntemden bağımsız) veya bu yollarda GET dışındaki yöntemleri 405 ile reddedin; ayrıca F-13'teki epoch/durum kontrolünü ekleyin.
- **Kabul testi:** WebTestClient ile token'sız `HEAD` ve `OPTIONS`/`POST` → 401/405; repository mock'u hiç çağrılmıyor; mevcut GET testleri geçmeye devam ediyor.
- **Bağımlılıklar:** Yok · **Efor:** S (tek metot + test) · **Açık PR örtüşmesi:** Yok (#104 waitlist/ops exporter'a dokunuyor, bu filtreye değil) · **Üretim ilgisi:** plausible (pinli gateway aynı kodu içeriyor; canlıda denenmedi — yetki yok).

### F-02 — Yedekleme, MinIO aynası başarısız/eksikken `COMPLETE` yazıp offsite'a yüklüyor
- **Kategori:** Operasyonel/DR kusuru · **Önem:** Yüksek · **Öncelik:** P0 · **Güven:** Yüksek · **Durum:** Doğrulandı (sentetik harness)
- **Etkilenen temel çizgi:** A (üretim yolu: `scripts/run-production-backup.sh:12` → `backup-hosted-beta.sh`; invite-production: `scripts/azure/invite-production-backup-run.sh:125`) · **Bileşen:** yedekleme orkestratörü
- **Dosya/satır:** `scripts/backup-hosted-beta.sh:104` (`parkio_backup_allow_complete "${DEST_DIR}" "${DB_FAILED}"` — `MINIO_OK` geçirilmiyor); `scripts/lib/backup-common.sh:403-428` (yalnızca DB hatası + ledger JSON); `scripts/backup-minio.sh:63` (mirror'dan önce `mkdir -p`), `backup-common.sh:551` (`seal_minio` kısmi ağacı mühürlüyor).
  - İkincil (aynı sınıf, ayrı betik): `scripts/backup-databases.sh:131-134` `failures` sayacına bakmadan `write_stamp_integrity && offsite_upload`; `:140-142` başarısız koşuda da 14 günden eski damgaları siliyor.
- **Gözlenen:** Orkestratör kopyası, mirror ortasında başarısız olan MinIO adımıyla: çıkış 1, manifest `"minioOk": 0`, **yine de** `COMPLETE`, `SHA256SUMS`, `minio.tar.gz.enc` üretildi ve `mc mirror` + `mc cp …/COMPLETE` offsite çağrıldı. Tekil betik, 10 dump + ledger başarısızken “11 failure(s)” ile çıktı ama `COMPLETE` + offsite yükleme yaptı.
- **Beklenen:** DB'ler, ledger **ve** MinIO başarılı değilse `COMPLETE` yok, offsite yükleme yok; başarısız koşu son iyi yedekleri budamamalı.
- **Kök neden:** 5414cf71 fail-closed düzeltmesi yalnızca DB/ledger'ı kapsadı; MinIO sonucu ve tekil betik kapıya bağlanmadı.
- **Tetikleyici:** MinIO/mc hatası, ağ kesintisi, disk dolması sırasında gece yedeği.
- **Etki:** Offsite'ta “COMPLETE” işaretli ama medya nesneleri eksik bir damga; geri yüklemede DB satırları olmayan nesnelere işaret eder. F-03 ile birleşince DR runbook bu damgayı sorgusuz geri yükler.
- **Kanıt:** `evidence/repro-backup/` (`RESULT.md`, `h1-backup-manifest.json`, `h1-stamp-listing.txt`, `h1-offsite-calls.log`, `h2-mc.log`, stub'lar). Mevcut `scripts/test-backup-production-fail-closed.sh` 17/17 geçiyor ama bu yolları uçtan uca çalıştırmıyor (F-26).
- **Mevcut azaltım:** `restore-stamp-preflight.py:169` `minioOk!=1`'i yalnızca drill-01 için reddediyor.
- **Asgari düzeltme:** `allow_complete`'e `MINIO_OK` ekleyip `!=1` ise reddet; başarısızlıkta kısmi `minio/` ağacını sil; `backup-databases.sh`'de `failures==0` ve `allow_complete` şartı; budamayı yalnızca başarılı COMPLETE'ten sonra yap ve son N tam damgayı koru.
- **Kabul testi:** Gerçek orkestratörü stub'lanmış alt adımlarla çalıştıran test: MinIO hatası → `COMPLETE` yok, `mc` çağrısı yok, çıkış ≠0; DB hatası → aynı; başarısız koşudan sonra önceki tam damga duruyor.
- **Bağımlılıklar:** F-26 (test altyapısı) · **Efor:** S–M · **Açık PR örtüşmesi:** **Var** — PR #104 `scripts/backup-hosted-beta.sh`'ye COMPLETE sonrası hook ekliyor; MinIO kapısını düzeltmiyor. Birleştirme sırası koordine edilmeli. · **Üretim ilgisi:** plausible (üretim yolu bu betik; canlı yedeklerin durumu bilinmiyor).

### F-03 — DR geri yükleme yolu bütünlük, `success` ve yeni erasure ledger kontrolü yapmıyor
- **Kategori:** Operasyonel/DR kusuru; gizlilik (silinen kullanıcıların geri dönmesi) · **Önem:** Yüksek · **Öncelik:** P1 · **Güven:** Yüksek · **Durum:** Doğrulandı (kod)
- **Dosya/satır:** `scripts/restore-hosted-beta.sh:46-213` — `parkio_backup_verify_stamp` (COMPLETE/SHA256SUMS) çağrısı yok, manifest `success`/`minioOk` kontrolü yok, yalnızca damganın kendi `erasure-tombstones.json`'u yeniden oynatılıyor (`:180-190`); `docs/operations/disaster-recovery-runbook.md:31` `--manifest backup-artifacts/backup-current.json` kullanıyor; `scripts/backup-hosted-beta.sh:129` `backup-current.json`'u başarısız koşularda da üzerine yazıyor. Doğru birleştirme aracı `scripts/lib/restore-erasure-ledger.py` yalnızca drill-01'de kullanılıyor. `scripts/lib/erasure-tombstones.sh:92,101` üretim modunu yalnızca `"1"` ile tanıyor.
- **Gözlenen/Beklenen:** Runbook en son (başarısız olabilecek) damgayı geri yükler; beklenen: yalnızca doğrulanmış, `success=1` son iyi damga + damgadan sonraki erasure'ların uygulanması.
- **Tetikleyici:** Host kaybı sonrası runbook'un izlenmesi.
- **Etki:** Eksik/bozuk veri geri yükleme; damgadan sonra hesabını silmiş kullanıcıların verisinin geri gelmesi (KVKK/GDPR). “Timestamp-only erasure coverage” tarihsel riski: drill-01'deki “supplemental covered-through” girdisi operatör tarafından beyan edilen bir zaman damgası, doğrulanamıyor.
- **Kanıt:** Kod okuması; ops ajanı ve baş denetçi tarafından satır bazında kontrol edildi. Çalıştırılmadı (Docker/DB gerektirir).
- **Asgari düzeltme:** `restore-hosted-beta.sh` içinde `restore-stamp-preflight.py` + ledger birleştirmesini zorunlu kıl; `success!=1` reddet; `backup-current.json`'u yalnızca başarılı koşuda güncelle ve ayrı bir `backup-last-good.json` tut; üretim modu bayrağını normalize et.
- **Kabul testi:** Başarısız manifestle restore → reddedilir; damgadan sonra silinmiş sentetik kullanıcı → geri yüklemede yok.
- **Bağımlılıklar:** F-02 · **Efor:** M · **Açık PR örtüşmesi:** #102/#104 off-host erasure kurtarmayı ekliyor (bekliyor, çözülmüş sayılmadı); `restore-drill-01.sh`'ye dokunuyor · **Üretim ilgisi:** plausible.

### F-04 — Kanonik üretim compose setinde Alertmanager devre dışı
- **Kategori:** Operasyonel boşluk (uyarı teslimi) · **Önem:** Yüksek · **Öncelik:** P0 (canlı durumu doğrula) · **Güven:** Yüksek (repo) / Düşük (canlı) · **Durum:** Doğrulandı (config render), canlı bilinmiyor
- **Dosya/satır:** `docker/compose.production.files` → `docker/docker-compose.azure-hosted-beta.yml:251-258` (`alertmanager`, `loki`, `promtail`, `tempo` → `profiles: [azure-disabled-observability]`); `docker/prometheus/prometheus.yml:38-40` hâlâ `alertmanager:9093`'ü hedefliyor; `scripts/install-civo-alert-slack-webhook.sh:62` Alertmanager'ı elle `--profile … up` ile başlatıyor; `scripts/lib/deploy-common.sh:118` alertmanager'ı DISABLED listeliyor.
- **Gözlenen:** `docker compose --env-file docker/.env.azure-hosted-beta.example <üretim seti> config` → 32 servis; alertmanager/loki/promtail/tempo yok (ops ajanı çalıştırdı; baş denetçi YAML'ı doğruladı).
- **Beklenen:** Uyarı teslim zinciri kanonik deploy setinin parçası olmalı ve her deploy sonrasında çalıştığı doğrulanmalı.
- **Etki:** BackupFailed/BackupStale ve diğer kurallar değerlendirilir ama elle başlatma yapılmadıysa veya sonraki bir `up`/yeniden oluşturma sonrası kaybolduysa kimseye ulaşmaz. CI yalnızca mock alıcıya teslimi kanıtlıyor (`observability-validation.yml:207`); gerçek Slack teslimi yalnızca elle tetiklenen `alerting-operator-acceptance.yml` ile.
- **Asgari düzeltme:** Alertmanager'ı üretim setinde etkin bir profil/overlay'e taşı; deploy sonrası `amtool`/`/-/ready` ve Prometheus `alertmanagers` hedefi için smoke kontrolü ekle; `Watchdog` (her zaman ateşleyen) uyarısı ile dış “dead man's switch”.
- **Kabul testi:** Üretim seti config render'ında alertmanager var; deploy smoke'u Prometheus'un aktif alertmanager hedefini gördüğünü doğruluyor; sentetik uyarı mock alıcıya ulaşıyor.
- **Efor:** S · **Açık PR örtüşmesi:** Yok · **Üretim ilgisi:** unknown (host'ta elle başlatılmış olabilir).

### F-05 — Sentetik harita anahtarı deploy guard'ı çalıştırılamaz ve atlatılabilir
- **Kategori:** Sürüm güvenliği kusuru · **Önem:** Yüksek · **Öncelik:** P1 · **Güven:** Yüksek · **Durum:** Doğrulandı
- **Dosya/satır:** `scripts/guard-web-synthetic-map-deploy.sh` git modu **100644** (`git ls-tree HEAD`), `scripts/parkio-prod-compose.sh:43` betiği doğrudan çağırıyor (`set -euo pipefail`); `scripts/test-guard-web-synthetic-map-deploy.sh` de 100644 ve hiçbir workflow'da çalıştırılmıyor; guard mantığı `:16-52` tek bir digest (`8d9bfca4…`) ve tam eşleşen `^VITE_MAPTILER_KEY=<sentetik>$` satırı; `frontend/apps/web/scripts/verify-bundle-env.mjs:33` yalnızca anahtarın boş olmadığını kontrol ediyor.
- **Gözlenen:** `bash scripts/test-guard-web-synthetic-map-deploy.sh` → 5/5 FAIL “Permission denied” (exit 126). Kopya üzerinde 8 varyant: yalnızca düz satır bloklandı; tırnaklı değer, `export ` öneki, sondaki boşluk, CRLF ve depodaki diğer CI yer tutucuları (`ci-public-map-key`, `SECRET_SENTINEL_MAPTILER_PUBLIC_KEY`, `REPLACE_ME`) geçti. Önceden derlenmiş pinli web imajı için env dosyası kontrolü anlamsız (VITE değerleri build'de gömülür). Guard yalnızca argümanlarda tam `up` varsa çalışıyor; `PARKIO_SKIP_WEB_MAP_GUARD=1` ile kapatılabiliyor.
- **Beklenen:** Temiz checkout'tan `parkio-prod-compose.sh up` guard'ı gerçekten çalıştırmalı; guard pinli imajın içindeki bundle'ı üretim sözleşmesine göre doğrulamalı.
- **Etki:** İki olası sonuç: (a) her korumalı `up` başarısız olur ve operatörler guard'ı kapatmaya itilir; (b) başka bir sentetik anahtarla derlenmiş imaj geçer → üretimde boş harita (tarihsel risk: PR #91 geri alması). Host checkout'unun dosya modu bilinmiyor.
- **Kanıt:** Frontend ajanının test çıktısı ve varyant tablosu; mod bilgisi baş denetçi tarafından `git ls-tree` ile doğrulandı.
- **Asgari düzeltme:** `bash "$ROOT/scripts/guard…"` ile çağır veya `+x`; guard'ı pinli digest'ten bundle çıkarıp desen tabanlı reddetme (`ci-`, `synthetic`, `SECRET_SENTINEL`, `REPLACE_ME`, `dummy`) yapacak şekilde yeniden yaz; testi CI'a ekle.
- **Kabul testi:** CI'da guard testi 0 ile çıkar; tüm varyantlar exit 1; sentetik anahtarlı bir bundle içeren yerel imaj reddedilir.
- **Efor:** S–M · **Açık PR örtüşmesi:** Yok · **Üretim ilgisi:** plausible.

### F-06 — CI rollback workflow'ları önceki koşunun manifest artifact'ını indiremiyor
- **Kategori:** Operasyonel kusur · **Önem:** Orta · **Öncelik:** P1 · **Güven:** Yüksek · **Durum:** Doğrulandı (kod; actions/download-artifact@v4 belgelenmiş davranışı)
- **Dosya/satır:** `.github/workflows/hosted-beta-deploy.yml:199-204`, `.github/workflows/invite-production-deploy.yml:730-734` — `actions/download-artifact@v4` yalnızca `name:` ile (`run-id`/`github-token` yok).
- **Gözlenen/Beklenen:** v4 varsayılan olarak yalnızca mevcut koşunun artifact'larını indirir; rollback koşusunda bu artifact yok → adım başarısız (invite: `test -n "$manifest"`). Beklenen: önceki koşunun `run-id`'si ile indirme.
- **Etki:** Belgelenmiş CI rollback yolu çalışmıyor; geri alma host üzerinde elle adımlara bağlı (`scripts/rollback-*.sh`).
- **Asgari düzeltme:** `run-id` girdisi + `github-token` + `permissions: actions: read`.
- **Kabul testi:** Staging ortamında önceki koşu artifact'ıyla rollback dispatch'i başarılı.
- **Efor:** S · **Açık PR örtüşmesi:** Yok · **Üretim ilgisi:** plausible.

### F-07 — Zamanlanmış workflow'lar ve Dependabot bayat `master`'ı hedefliyor
- **Kategori:** CI/CD riski · **Önem:** Orta · **Öncelik:** P1 · **Güven:** Yüksek · **Durum:** Doğrulandı
- **Kanıt:** Varsayılan dal `master` (`git ls-remote --symref origin HEAD`), api'nin 498 commit gerisinde. `master` üzerinde cron'lu: `security-ci.yml` (Pzt 03:23), `supply-chain.yml`, `backend-integration.yml` (gece), `runtime-validation.yml`, `chaos-validation.yml`, `performance-smoke.yml`, `staging-verification.yml` — `ref: api` checkout yok. Yalnızca restore drill'leri `scheduled-restore-drills.yml` ile `--ref api`'ye yönlendirilmiş (tarihsel “stale branch” riski yalnızca bunlar için düzeltilmiş). api'deki `runtime-baseline.yml` cron'u hiç çalışmaz. `.github/dependabot.yml` 15 girdinin hiçbirinde `target-branch` yok → 20 açık Dependabot PR'ı `master`'a (#1–#41); `api` otomatik güvenlik güncellemesi almıyor. `backup-restore-drill.yml:89-91` `cancel-in-progress` ile api'ye bir push dispatch edilmiş drill'i sessizce iptal edebilir.
- **Etki:** Haftalık güvenlik/sürüklenme taramaları deploy edilen kodu taramıyor; bağımlılık CVE'leri api'ye ulaşmıyor (ör. #29 bcprov 1.85 master'a açık; api'de ayrıca `g06a-bouncycastle-cve-2026-8763` dalı var).
- **Asgari düzeltme:** Dispatcher'ı tüm zamanlanmış workflow'lara genişlet veya her birinde `ref: api` checkout; Dependabot'a `target-branch: api`; dispatch koşularında `cancel-in-progress: false`. Uzun vadede varsayılan dalı api ile hizala.
- **Kabul testi:** Pazartesi security-ci koşusu `head_sha == origin/api`; yeni Dependabot PR'ları `api` tabanlı.
- **Efor:** S · **Açık PR örtüşmesi:** Yok · **Üretim ilgisi:** not applicable (CI).

### F-08 — CodeQL api HEAD'de 4 yeni “high” uyarı raporluyor; içerik bilinmiyor
- **Kategori:** Doğrulama boşluğu · **Önem:** Bilinmiyor · **Öncelik:** P1 (triage) · **Güven:** — · **Durum:** Doğrulama boşluğu
- **Kanıt:** PR #44 (api HEAD `aa865a25`) check-run `CodeQL` (id 107336332696): “4 new alerts including 4 high severity security vulnerabilities”; alt işler `CodeQL (java-kotlin)` ve `(javascript-typescript)` başarılı. Annotasyonlar/uyarı listesi bu oturumdaki araçlarla okunamadı.
- **Neden doğrulanamadı:** Code scanning API'sine erişim yok. Bu denetim bu 4 uyarının F-01…F-40 ile örtüşüp örtüşmediğini bilmiyor.
- **Gerekli adım:** Security → Code scanning'de `pr:44 tool:CodeQL is:open` listesini triage et; her biri için gerçek/yanlış pozitif kararını kaydet.
- **Efor:** S · **Üretim ilgisi:** unknown.

### F-09 — Slack relay worker crash-loop; `max_attempts` atlanıyor, sınırsız tekrar gönderim
- **Kategori:** Veri/olay tutarlılığı, güvenilirlik · **Önem:** Orta · **Öncelik:** P1 · **Güven:** Yüksek · **Durum:** Doğrulandı (yeniden üretildi)
- **Dosya/satır:** `scripts/slack_biz/transport.py:65-131` (`http.client.HTTPException` alt sınıfları — `IncompleteRead`, `BadStatusLine`, `LineTooLong` — yakalanmıyor; `resp.read` satır 72); `delivery.py:95` korumasız çağrı; `worker.py:146-151` `while True` döngüsünde try/except yok; `store.py:283-311` lease geri alımı `attempts`'ı artırmıyor; `deploy/civo/parkio-slack-biz-worker.service:21-22` `Restart=on-failure`, `RestartSec=10s`.
- **Gözlenen:** `poison_probe.py`: üç istisna türü de UNCAUGHT. `poison_worker.py` (MAX_ATTEMPTS=3, mock transport): 5 çevrimde 3 çökme, **3 gönderim**, satır `{'status': 'in_flight', 'attempts': 0}`.
- **Beklenen:** Belirsiz sonuç olarak sınıflandırılıp sınırlı yeniden deneme → `delivery_unknown`; worker çökmemeli.
- **Tetikleyici:** Slack/proxy/LB'den kesik veya bozuk HTTP yanıtı.
- **Etki:** Aynı waitlist bildiriminin her restart'ta yeniden gönderilmesi (Slack'e ulaşmış olabilir) — sınırsız çift gönderim; kuyruk başı tıkanması; systemd yeniden başlatma döngüsü.
- **Kanıt:** `evidence/repro-slack-worker/` (`poison_probe.py`, `poison_worker.py`, `output.txt`). Komut: `cd <scratch>/scripts && python3 poison_probe.py && python3 poison_worker.py <scratch>`.
- **Asgari düzeltme:** `HTTPException`'ı AMBIGUOUS olarak yakala; `_process_item`'ı catch-all ile sar (`mark_retry`/`mark_dead`); `reclaim_expired_leases` `attempts`'ı artırsın.
- **Kabul testi:** `poison_worker.py` → en fazla MAX_ATTEMPTS gönderim, durum `delivery_unknown`, worker çökmesi yok.
- **Efor:** S · **Açık PR örtüşmesi:** #104 `install-relay.sh` ve Slack uzlaştırmasına dokunuyor, transport/worker'a değil · **Üretim ilgisi:** plausible (relay'in canlıda etkin olup olmadığı bilinmiyor).

### F-10 — Erasure ack'i DB işlemi içinde; kısa yeniden deneme; tombstone'lar okunmuyor
- **Kategori:** Veri bütünlüğü / gizlilik (KVKK/GDPR) · **Önem:** Orta · **Öncelik:** P1 · **Güven:** Orta-Yüksek · **Durum:** Doğrulandı (kod)
- **Dosya/satır:** `parking/application/AccountErasureHandler.java:40-48` (`@Transactional` içinde `eraseLocal()` → HTTP `ackClient.acknowledge()` → commit); aynı desen media `:49-55`, moderation `:40-45`, ai-validation `:40-45`, gamification `:40-45`, analytics `:39-44`; hata işleyici `DefaultErrorHandler(DLT, FixedBackOff(500ms, 2))` (ör. `ParkingKafkaConsumerConfig.java:91-92`). `erased_user_tombstones` tüm servislerde yazılıyor, okuyan kod yok. Parking erasure'da spot `description`/`address_text` serbest metni kalıyor (`AccountErasureHandler.java:50-64`).
- **Gözlenen:** (a) ack başarılı + commit başarısız → auth SUCCESS kaydeder, veri durur; (b) auth-service ~1,5 sn erişilemezse işlem geri alınır, olay DLT'ye gider; otomatik yeniden sürüş yok (yalnızca `ErasureStuckGaugeJob` göstergesi).
- **Beklenen:** Commit sonrası ack (outbox veya `afterCommit` + kendi yeniden denemesi); dakikalar mertebesinde üstel geri çekilme; geç gelen olaylar tombstone ile reddedilmeli.
- **Etki:** Sessizce eksik kalan veya elle DLT yeniden sürüşüne kadar takılı kalan silme talepleri; silinmiş kullanıcı için geç olayla veri yeniden oluşması.
- **Asgari düzeltme:** Ack'i yerel outbox'a taşı; erasure dinleyicisine uzun üstel backoff/retry topic; yazma yolları ve tüketicilerde tombstone kontrolü; serbest metin alanlarını temizle.
- **Kabul testi:** Ack istemcisi ilk N çağrıda hata → veri silinmiş, ack tam bir kez; commit zorla başarısız → ack gönderilmemiş; silinmiş kullanıcı için `ParkingSessionStarted` reddedilir.
- **Efor:** M (6 servis) · **Açık PR örtüşmesi:** #102/#104 off-host erasure kurtarma (farklı katman) · **Üretim ilgisi:** plausible.

### F-11 — Üretim imaj kökeni ve bütünlüğü
- **Kategori:** Deploy tekrarlanabilirliği / tedarik zinciri · **Önem:** Orta · **Öncelik:** P1 · **Güven:** Yüksek · **Durum:** Doğrulandı
- **Kanıt:** `docker/compose.production.files` yalnızca gateway/media/parking (`gmp-release-pins.yml`), auth, web için digest pin içeriyor. **user, gamification, notification, moderation, ai-validation, analytics** için imaj yok — `docker/docker-compose.apps.yml` (ör. `:229-233`) `build: context: ..` → host checkout'undan build (config render: 6 servis build-only). `gmp-release-pins.yml:17-19` parking/media için kaynak revizyonu yok (“current live production values”). Servis Dockerfile'ları `eclipse-temurin:21-jre` tag'i; postgres/redis/caddy/nginx tag'li. `release.yml` SBOM + cosign yalnızca `vars.PUBLISH_IMAGES == 'true'` iken; depoda hiçbir yerde `cosign verify`/`gh attestation verify` yok. `hosted-beta-deploy.yml:51-54,157-161` ortam/ref kapısı olmadan imzasız imaj + değişken `beta-latest` tag'i itiyor.
- **Etki:** Üretimdeki 6 servisin hangi kaynaktan derlendiği depodan belirlenemez; host sürüklenmesi; yeniden üretilemez kurtarma.
- **Asgari düzeltme:** 10 servisin tamamı için CI'da derlenmiş, digest pinli imajlar; pin dosyalarında kaynak SHA + config ID; deploy öncesi imza/attestation doğrulaması; temel imajları digest'e pinle.
- **Kabul testi:** Üretim seti config render'ında `build:` yok; her `image:` `@sha256:`; deploy betiği imza doğrulaması başarısızsa durur.
- **Efor:** M · **Açık PR örtüşmesi:** #56 (release artifact CI kabulü, taslak) · **Üretim ilgisi:** plausible.

### F-12 — Deploy profilleri arasında sapma
- **Kategori:** Operasyonel risk · **Önem:** Orta · **Öncelik:** P1 · **Güven:** Yüksek · **Durum:** Doğrulandı (kod)
- **Kanıt:** Kanonik set (`compose.production.files`) ≠ `scripts/lib/deploy-common.sh:116` seti (base, apps, **images**, hosted-beta, azure, gmp-pins; `auth-registration-env`, `auth-release-pin`, `web-release-pin` **yok**) ≠ DR runbook adım 3 (`disaster-recovery-runbook.md:26`, düz hosted-beta seti) ≠ invite-production (`managed-db` + `invite-*`). 22+ compose dosyası. Depoda olmayan host durumu: `docker-compose.gmp-recovery-prior.yml`, backup cron satırı, elle Alertmanager başlatma (F-04), self-hosted runner'daki `docker/.env` (`actions/checkout` varsayılan `clean: true` bunu silebilir — Şüpheli).
- **Etki:** `deploy-hosted-beta.sh` ile yapılan bir deploy pinli auth/web imajlarını tag imajlarıyla değiştirip kayıt modu env'ini düşürebilir; DR sırasında yanlış set ayağa kalkar.
- **Asgari düzeltme:** Tek kaynak dosya listesi (`compose.production.files`) tüm deploy/rollback/DR betiklerinde kullanılmalı; CI'da set eşitliği testi.
- **Kabul testi:** `deploy-common.sh`, DR runbook ve `parkio-prod-compose.sh` aynı config hash'ini üretir.
- **Efor:** S–M · **Açık PR örtüşmesi:** Yok · **Üretim ilgisi:** plausible.

### F-13 — Yetki iptali mevcut access token'larda uygulanmıyor
- **Kategori:** Güvenlik zayıflığı (CWE-613) · **Önem:** Orta · **Öncelik:** P2 · **Güven:** Yüksek · **Durum:** Doğrulandı (kod)
- **Dosya/satır:** `auth/application/admin/AdminApplicationService.java:187` `revokeAllSessions`, `:203` `revokeSession`, `:255` `revokeRole` → `bumpSessionEpoch()` çağırmıyor (yalnızca `suspendUser :159` ve kullanıcı logout-all `AuthApplicationService.java:412` çağırıyor). `gw/infrastructure/security/WaitlistAdminSecurityWebFilter.java:79-95` yalnızca JWT'yi doğruluyor; `SessionEpochGlobalFilter`/`AccountStatusGlobalFilter` yerel controller'lar için çalışmıyor.
- **Etki:** Rolü alınan ADMIN/MODERATOR veya oturumları iptal edilen hesap, access token ömrü boyunca (varsayılan 15 dk, auth `application.yml:170`) yetkili kalır; iptal edilmiş/askıya alınmış bir ADMIN token'ı waitlist PII export'unu 15 dk kullanabilir. Olay müdahalesini zayıflatır.
- **Ön koşul:** Çalınmış/iptal edilmiş ayrıcalıklı token.
- **Asgari düzeltme:** Bu metotlarda epoch artır; waitlist filtresinde epoch + hesap durumu kontrolü (fail-closed).
- **Kabul testi:** Rol iptalinden sonra eski token `/api/v1/admin/users` ve `/api/v1/waitlist/export` üzerinde epoch önbellek penceresi (30 sn) içinde 401.
- **Efor:** S · **Açık PR örtüşmesi:** Yok · **Üretim ilgisi:** plausible.

### F-14 — Hesap varlığı sızıntıları (enumeration)
- **Kategori:** Güvenlik zayıflığı (CWE-204/CWE-208) · **Önem:** Orta · **Öncelik:** P2 · **Güven:** Orta-Yüksek · **Durum:** Doğrulandı (kod); zamanlama ölçülmedi
- **Kanıt:**
  1. INVITE modu: `auth/application/RegistrationGateService.java:49` yalnızca token'ın varlığını kontrol ediyor; `AuthApplicationService.java:143` `existsByEmail` → 409 `EMAIL_ALREADY_EXISTS`, davetiye `:151`'de doğrulanıyor → sahte token ile kayıtlı/kayıtsız e-posta ayırt edilir. Varsayılan mod `closed` (`docker-compose.auth-registration-env.yml`), canlı mod bilinmiyor.
  2. Login `:182` kullanıcı yoksa BCrypt'i atlıyor (~50–100 ms fark); forgot/resend `:216-227, 252-273` yalnızca hesap varsa Resend'e senkron HTTP çağrısı (2 sn/5 sn timeout) — yanıt gövdesi tekdüze, süre değil.
  3. Waitlist: `WaitlistApplicationService.java:269-271` + `WaitlistExceptionHandler.java:90-97` — e-posta sağlayıcısı kesintisinde 503 yalnızca uygun PENDING satır varsa.
- **Etki:** Kapalı/davetiyeli beta sırasında kayıtlı e-postaların tespiti; oltalama hedeflemesi. Gateway sınırı 5 rps/IP.
- **Asgari düzeltme:** Önce davetiyeyi doğrula (veya tek tip hata); eksik kullanıcıda sahte BCrypt; e-posta gönderimini asenkron (outbox) yap; waitlist resend'de hep 202.
- **Kabul testi:** INVITE modunda sahte token ile mevcut/yeni e-posta aynı yanıt; N örnekte medyan gecikme farkı < ~10 ms.
- **Efor:** S–M · **Açık PR örtüşmesi:** Yok (`prep/auth-resend-delivery-readiness` dalı var, PR değil) · **Üretim ilgisi:** plausible.

### F-15 — Yalnızca e-postaya bağlı kalıcı hesap kilitleme
- **Kategori:** Güvenlik zayıflığı (hedefli DoS, CWE-645) · **Önem:** Orta · **Öncelik:** P2 · **Güven:** Yüksek · **Durum:** Doğrulandı (kod)
- **Dosya/satır:** `auth/infrastructure/security/RedisLoginFailureTracker.java:32-68` (anahtar yalnızca e-posta; her başarısızlıkta 24 sa TTL yenileniyor; 20+ başarısızlıkta 1 sa kilit; kilit paroladan önce kontrol ediliyor); çağrı `AuthApplicationService.java:176`. Parola sıfırlama sayacı temizlemiyor.
- **Etki:** ~20 hatalı deneme + saatte 1 deneme ile herhangi bir hesap (adminler dâhil) süresiz kilitlenir.
- **Asgari düzeltme:** Hesap+IP/cihaz anahtarı, hesap başına kademeli gecikme (sert kilit yerine), sıfırlamada sayacı temizle, N hatadan sonra CAPTCHA/PoW.
- **Kabul testi:** IP A'dan 25 hata sonrası IP B'den doğru giriş başarılı (veya yalnızca step-up).
- **Efor:** S–M · **Üretim ilgisi:** plausible.

### F-16 — İç güven modeli
- **Kategori:** Mimari risk · **Önem:** Orta · **Öncelik:** P2 · **Güven:** Yüksek (kod) · **Durum:** Doğrulandı; istismar iç erişim gerektirir
- **Kanıt:** Tüm servisler tek `X-Gateway-Auth` sırrını kabul ediyor ve servisler arası çağrılarda da kullanıyor (ör. `auth/infrastructure/web/GatewayAuthFilter.java:146`); `auth/presentation/AdminController.java:58-59` rol kararını JWT yerine `X-User-Roles` başlığından alıyor; `/internal/**` (erasure ack/replay `InternalErasureController.java:37,50`, session-epoch, bootstrap) yalnızca bu sırla korunuyor; `/actuator`, `/v3/api-docs`, `/swagger-ui` sır kontrolünü atlıyor (`parking …/GatewayAuthFilter.java:77-83`). Kafka `PLAINTEXT` (`docker/docker-compose.yml:206`), ACL yok; olay zarfı imzasız. Önceki denetimin S1/S2 bulguları **hâlâ geçerli**. Üretimde Kafka ve servis portları yayınlanmıyor (`docker-compose.hosted-beta.yml` `ports: !reset []`) — ön koşul: herhangi bir iç konteynerde kod yürütme veya sırrın sızması.
- **Etki:** Tek bir servisin ele geçirilmesi → her serviste her kullanıcı/SUPER_ADMIN olarak davranma, sahte erasure ack'leri, sahte `UserSuspended`/puan olayları.
- **Asgari düzeltme:** auth-service'te yetkiyi `SecurityContext` (JWT) üzerinden ver; orta vadede servis başına kimlik bilgisi/mTLS veya gateway imzalı kısa ömürlü iç JWT; Kafka SASL + topic ACL.
- **Kabul testi:** Geçerli USER JWT + sahte `X-User-Roles: ADMIN` + doğru gateway sırrı → auth `/api/v1/admin/users` 403.
- **Efor:** S (auth header) / L (mTLS, Kafka ACL) · **Üretim ilgisi:** plausible.

### F-17 — Konum arama/görüntüleme logları süresiz
- **Kategori:** Gizlilik (veri minimizasyonu) · **Önem:** Orta · **Öncelik:** P2 · **Güven:** Yüksek · **Durum:** Doğrulandı (kod)
- **Dosya/satır:** `parking/application/ParkingApplicationService.java:619-620` (kullanıcı id + lat/lng + yarıçap), `:150` (görüntüleme), `V6__create_parking_spot_search_logs.sql`; `RetentionCleanupJob.java:13,55,71` yalnızca outbox/inbox temizliyor. Önceki denetim #7/#20 hâlâ geçerli.
- **Etki:** Kullanıcı başına sınırsız hassas konum geçmişi (KVKK veri minimizasyonu).
- **Asgari düzeltme:** Yapılandırılabilir TTL (30–90 gün) ile temizlik + `created_at` indeksi veya koordinatların kabalaştırılması.
- **Kabul testi:** TTL'den eski satırlar iş tarafından silinir, yeniler kalır. · **Efor:** S · **Üretim ilgisi:** plausible (özelliğin canlıda kullanımı bilinmiyor).

### F-18 — Waitlist rıza kanıtı eksik
- **Kategori:** Gizlilik/ürün · **Önem:** Orta · **Öncelik:** P2 · **Güven:** Yüksek · **Durum:** Doğrulandı (kod)
- **Kanıt:** `SubmitWaitlistRequest.java:19-33` rıza boolean'ı veya metin sürümü yok; `web/marketing/waitlist.js:297-307` onay kutusu yalnızca istemci tarafında; V1–V5 migration'larında sürüm sütunu yok; CSV export `confirmed_at` ve `client_consent_timestamp`'i içermiyor (`WaitlistController.java:130-138`); PENDING iken yeniden gönderim yeni rıza zamanını ve adı atıyor (`WaitlistApplicationService.java:107-113`).
- **Etki:** Hangi aydınlatma/rıza metninin kabul edildiği kanıtlanamaz; API'yi doğrudan çağıran bir istemci rıza olmadan kayıt oluşturabilir (double opt-in onayı yine gerekli).
- **Asgari düzeltme:** Zorunlu `consent: true` + `consent_text_version`; export'a `confirmed_at`.
- **Kabul testi:** `consent:true`/sürüm olmadan gönderim → 400; satırda sürüm var. · **Efor:** S–M · **Üretim ilgisi:** plausible.

### F-19 — Pazarlama waitlist formu herhangi bir 503'te “kaydınız alındı” diyor
- **Kategori:** Yanıltıcı başarı mesajı (işlevsel) · **Önem:** Orta · **Öncelik:** P2 · **Güven:** Yüksek · **Durum:** Doğrulandı (tarayıcı, mock API)
- **Dosya/satır:** `web/marketing/waitlist.js:78-83` (kodu `WAITLIST_ADMISSIONS_DISABLED` olmayan her 503 → `EMAIL_DELIVERY_FAILED` → “kaydınız alındı ama e-posta gönderilemedi”); backend açık kod gönderiyor: `WAITLIST_EMAIL_DELIVERY_FAILED` (`WaitlistExceptionHandler.java:96`). Ayrıca `:118-130` onay/çıkış sayfasında her 202-dışı yanıt “şu an kaydedilemedi, tekrar deneyin” (geçersiz/süresi dolmuş token için yeniden deneme işe yaramaz).
- **Gözlenen:** HTML gövdeli mock 503 → “kaydedildi” mesajı. `evidence/screenshots/waitlist-503-claims-saved.png`, `waitlist-confirm-invalid-token-generic.png`.
- **Etki:** Proxy/gateway kesintisinde hiçbir şey saklanmadığı hâlde kullanıcıya kayıt alındı denir; kullanıcı tekrar denemez → kayıp potansiyel abone.
- **Asgari düzeltme:** “kaydedildi” yalnızca `body.code === 'WAITLIST_EMAIL_DELIVERY_FAILED'`; diğer 503'ler `SERVER_ERROR`; token sayfalarında geçersiz/rate-limit/sunucu ayrımı.
- **Kabul testi:** `waitlist.adapter.test.mjs`'e kodsuz 503 ve HTML 503 vakaları. · **Efor:** S · **Açık PR örtüşmesi:** #93 (yalnızca stil) · **Üretim ilgisi:** plausible.

### F-20 — Harita hatasında boş harita; Explore hatasında yeniden deneme yok
- **Kategori:** UX/işlevsel · **Önem:** Orta · **Öncelik:** P2 · **Güven:** Yüksek · **Durum:** Doğrulandı (tarayıcı, mock)
- **Dosya/satır:** `frontend/apps/web/src/components/map/NearbySpotsMap.tsx:242-244` (`onError` yalnızca analitik olayı); `mapConfig.ts:57-58`; `pages/PublicExplorePage.tsx:110` (`retry: false`), `:164-165` (hata durumunda harita ve arama kaldırılıyor), ~`:277` (yeniden dene düğmesi yok). MapLibre atıf linki zoom kontrolünün altında kalıyor (axe `target-size` serious).
- **Gözlenen:** MapTiler 403 → işaretler boş tuval üzerinde, uyarı yok (`evidence/screenshots/web-explore-map-style-403-{desktop,mobile}.png`); tek bir 5xx sonrası çıkmaz ekran (`web-mobile-_explore.png`).
- **Etki:** Yanlış/kısıtlı sağlayıcı anahtarında (F-05 ile ilişkili) kullanıcı boş harita görür; geçici hatada sayfa yenilenmeden kurtulunamaz.
- **Asgari düzeltme:** Stil/tile hatasında `role=alert` banner + liste görünümüne yönlendirme (isteğe bağlı OSM raster yedeği); `refetch()` düğmesi, `retry: 1`; atfı kontrollerden ayır.
- **Kabul testi:** Playwright: `api.maptiler.com` 403 → görünür `role=alert`; ilk istek 503/ikinci 200 → yeniden dene sonrası işaretler. · **Efor:** S–M · **Üretim ilgisi:** plausible.

### F-21 — Şifre sıfırlama sayfası `?lang=` parametresini yok sayıyor
- **Kategori:** i18n kusuru (tarihsel “URL niyeti yerine kalıcı tarayıcı durumu” sınıfı) · **Önem:** Orta · **Öncelik:** P2 · **Güven:** Yüksek · **Durum:** Doğrulandı (tarayıcı)
- **Kanıt:** `AuthTransactionalEmailTemplates.java:151-158` verify ve reset linklerine `&lang=` ekliyor; yalnızca `RegisterPage.tsx:73` ve `VerifyEmailPage.tsx:25` okuyor; `ResetPasswordPage.tsx` ve `/login` okumuyor; başlangıç dili `locale-storage.ts:23` (localStorage → TR). Kayıtlı `tr` ile `/reset-password?token=…&lang=en` → Türkçe başlık, `html lang=tr` (`evidence/screenshots/web-reset-password-lang-en-ignored.png`). Tarihsel hata register/verify için düzeltilmiş, reset/login için değil.
- **Etki:** İngilizce e-posta alan kullanıcı Türkçe sıfırlama sayfası görür. Pazarlama sitesi URL'yi önceliklendiriyor (`i18n.js:517`) — doğru; ancak `waitlist.js:53-55` geri bildirim/payload dilini storage'dan alıyor (Şüpheli, storage engelliyse).
- **Asgari düzeltme:** Bootstrap'ta tüm rotalar için URL `lang`'ını bir kez çöz (`initI18n`); kalıcılaştırma politikasını ayrı karar olarak belirle.
- **Kabul testi:** `locale.spec.ts`: kayıtlı `tr` + `/reset-password?lang=en` → EN. · **Efor:** S · **Üretim ilgisi:** plausible.

### F-22 — Belediye doluluğu “LIVE” anlamı ve İZUM küçülme eskalasyonu
- **Kategori:** İşlevsel doğruluk · **Önem:** Orta · **Öncelik:** P2 · **Güven:** Orta · **Durum:** Doğrulandı (kod)
- **Kanıt:** Toplu devre dışı bırakma düzeltmesi (`fix/izum-incomplete-snapshot-no-mass-deactivate`, 4b48ed41/e74c0892) HEAD'in atası — **düzeltilmiş**. Kalan: (a) `parking/…/MunicipalFacilitySyncService.java:129-148` İZUM küçülmesinde her koşuda yalnızca WARN, N ardışık atlamada eskalasyon yok; (b) `isAuthoritativeSet` `status` argümanını yok sayıyor (`:226-246`), Javadoc'la çelişiyor; (c) İZUM/İSPARK gözlem zamanı olarak `fetchedAt` (`IzumNormalizer.java:134-141`, `IsparkNormalizer.java:68-72`) — kaynak dondurulmuş önbellek sunsa bile LIVE; (d) İZUM `status` (açık/kapalı) doluluk için yok sayılıyor; `free`/`occupied` ikisi de null kayıt geçerli (`IzumRecordValidator.java:17-23`) → LIVE + null.
- **Etki:** Kullanıcıya bayat veya kapalı otopark için “canlı” doluluk gösterilebilir; kapanan tesis süresiz aktif kalır. (Sunucu `availableSpaces`'i yalnızca LIVE/AGING'de döndürüyor — `PublicExploreQueryService.java:183` — dolayısıyla asıl risk yanlış “LIVE” etiketi.)
- **Asgari düzeltme:** K ardışık atlamada metrik/uyarı; kapalı durum veya null sayılar → UNAVAILABLE; kaynak zaman damgası yoksa freshness tavanı.
- **Kabul testi:** 3 ardışık küçük İZUM beslemesi → uyarı metriği; `status=closed` → UNAVAILABLE. · **Efor:** S–M · **Üretim ilgisi:** plausible.

### F-23 — Belediye besleme istemcilerinde boyut/süre sınırı yok
- **Kategori:** Kullanılabilirlik (CWE-400) · **Önem:** Orta · **Öncelik:** P2 · **Güven:** Yüksek · **Durum:** Doğrulandı (kod)
- **Kanıt:** `parking/…/client/IzumParkingClient.java:22-36` `body(JsonNode.class)` tamamen belleğe; İSPARK/ANPARK/Kayseri/Konya aynı (`KonyaParkingClient.java:45-53,107`); `SimpleClientHttpRequestFactory` yalnızca connect/read timeout (soket okuma başına) — damla damla yanıt kesilmez; yeniden denemeler çarpar. SSRF yok (URL'ler yalnızca `MunicipalSourceProperties`).
- **Etki:** Bozuk/ele geçirilmiş üst kaynak parking-service heap'ini tüketebilir (768 MB limit) veya sync worker'ı süresiz tutar; RUNNING lease sonraki koşuları engeller.
- **Asgari düzeltme:** Boyut sınırlı akış (kaynak başına 10–20 MB), toplam fetch deadline'ı, Jackson `StreamReadConstraints`.
- **Kabul testi:** WireMock ile sınır üstü gövde → FAILED/payload_too_large; yavaş gövde deadline içinde iptal. · **Efor:** S–M · **Üretim ilgisi:** plausible.

### F-24 — Paylaşılan DLT redrive'ı kayıtları yanlış topic'e gönderebilir
- **Kategori:** Veri/olay tutarlılığı · **Önem:** Orta · **Öncelik:** P2 · **Güven:** Yüksek · **Durum:** Doğrulandı (kod)
- **Kanıt:** `notification-service/…/NotificationKafkaConsumerConfig.java:75-77` tüm tüketiciler tek `parkio.dlt.notification` topic'ine; `tools/dlt-redrive/…/KafkaDltRedriveTool.java:50-63,114` DLT'nin tamamını baştan okuyup tek `--target-topic`'e yeniden yayımlıyor, `kafka_dlt-original-topic` başlığını kontrol etmiyor, offset commit etmiyor (her koşu aynı ilk N kaydı yeniden sürer). Yanlış topic'teki kayıtlar tüketicide debug log + ack ile düşer.
- **Etki:** Operatör kurtarması sırasında olay kaybı; tekrar koşularda çift yeniden sürüş (inbox dedup çiftleri sınırlar, yanlış yönlendirmeyi değil).
- **Asgari düzeltme:** Orijinal topic ≠ hedef ise atla/başarısız ol; `--original-topic` filtresi ve offset takibi.
- **Kabul testi:** Karışık DLT (A, B) + hedef A → yalnızca A kayıtları. · **Efor:** S · **Üretim ilgisi:** plausible (yalnızca operatör kullanımı).

### F-25 — NR bütçe ve Slack relay durumu yedeklenmiyor
- **Kategori:** DR/operasyon · **Önem:** Orta · **Öncelik:** P2 · **Güven:** Yüksek · **Durum:** Doğrulandı (kod)
- **Kanıt:** `scripts/newrelic_log_pilot/budget_gate.py:109-114` SQLite DB/dizin yoksa harcama 0 ile yeni satır; reddetme yalnızca limit değişince. Yedekleme betikleri yalnızca Postgres + MinIO. `docs/operations/backup-restore-readiness.md:213` bunu kabul ediyor. Test kontrolü (`/test/reset`, `/test/clock`) üretim overlay'lerinde `"off"` — doğru.
- **Etki:** Host yeniden kurulumu veya eski durum geri yüklemesi NR bütçesini sıfırlar/geri sarar (maliyet aşımı); relay dedup penceresi kaybolur → Slack yeniden oynatma riski. Tarihsel risk “operational-state recovery NR bütçelerini sıfırlıyor/Slack'i yeniden oynatıyor” **api'de hâlâ açık**; düzeltme #101/#103/#104'te **bekliyor**.
- **Asgari düzeltme:** #104'ün birleştirilmesi (kapsam içinde doğrulanarak); ayrıca bütçe DB'si yoksa üretimde fail-closed (açık onay olmadan yeni defter yok) — #103 bunu hedefliyor.
- **Kabul testi:** Üretim modunda defter yok → gate başlamaz/sevk etmez; yedek/geri yükleme sonrası harcama korunur. · **Efor:** M · **Açık PR örtüşmesi:** #101, #102, #103, #104 · **Üretim ilgisi:** plausible.

### F-26 — CI yanlış kırmızı / yanlış yeşil
- **Kategori:** Test/CI kalitesi · **Önem:** Orta · **Öncelik:** P2 · **Güven:** Yüksek · **Durum:** Doğrulandı
- **Kanıt:**
  1. `slack_biz relay acceptance (mock Slack)` PR #44'te kırmızı: `.github/workflows/slack-biz-relay-acceptance.yml:83-86` `origin/${{ github.base_ref }}` (= `master`) ile fark alıyor; `master`'da `docker/compose.production.files` yok; `validate-compose-integration.sh:32-42` dosya okuma hatası process substitution içinde `set -e`'yi tetiklemiyor → boş `-f` listesi → “no configuration file provided”. Relay kabul testleri o koşuda **geçti** (13 PASS / 1 NOT_EXECUTED: `kafka_live_consumer` ABSENT); yerelde W01–W24 24/24, Y03 15/15, Y03A 13+1 NOT_EXECUTED — yeniden üretildi. `workflow_dispatch` ile api'de HEAD kendisiyle kıyaslanıyor (anlamsız).
  2. `mobile-ci.yml:83` kanonik mobile-v2 `expo-doctor` adımı `continue-on-error: true` (“blocking” olarak tanımlı işte); legacy mobile işi (`:95`) expo-doctor paket sürüm sapması ile kırmızı.
  3. `scripts/test-guard-web-synthetic-map-deploy.sh` hiçbir workflow'da yok ve yerelde 5/5 FAIL (F-05).
  4. `scripts/test-backup-production-fail-closed.sh:138-165` yalnızca grep + kapı simülasyonu; betikleri uçtan uca çalıştırmıyor (F-02 kaçtı).
  5. CodeQL işi `vars.CODEQL_ENABLED` yoksa atlanıyor (nötr görünür) — değişken değeri okunamadı.
  6. Branch protection/zorunlu kontroller okunamadı → hangi kırmızının birleştirmeyi engellediği bilinmiyor.
- **Asgari düzeltme:** Merge-base ile fark al veya dosya yoksa açık mesajla atla; doctor'ı bloklayıcı yap; guard ve fail-closed testlerini uçtan uca yapıp CI'a bağla.
- **Kabul testi:** PR #44'te slack_biz işi yeşil veya açık “atlandı”; betiklere kasıtlı regresyon eklenince ilgili test kırmızı. · **Efor:** S–M · **Açık PR örtüşmesi:** #104 `validate-compose-integration.sh`'yi değiştiriyor (base sorununu düzeltmiyor).

### F-27 — Kurtarma kanıtı sentetik; RPO/RTO çelişkili
- **Kategori:** Operasyonel boşluk · **Önem:** Orta · **Öncelik:** P1 · **Güven:** Yüksek · **Durum:** Doğrulama boşluğu
- **Kanıt:** `docs/operations/backup-restore-readiness.md:269` gerçek üretim geri yükleme kanıtı olmadığını söylüyor; `:291` drill-01 parking satır sayısı parity FAIL kaydı; `:322` önerilen RTO 4 sa/8 sa “ölçülecek”; `disaster-recovery-runbook.md` 1–2 sa RTO; `runtime-sizing.md:225` RPO = son dump'tan beri geçen süre, WAL arşivi/PITR yok. CI drill'leri (Backup→restore→assert, Encrypted stamp→isolated restore) sentetik veriyle yeşil.
- **Eksik kanıt:** Gerçek (şifreli) bir üretim damgasının izole ortamda geri yüklenmesi, ölçülmüş RTO, MinIO geri yükleme + DB referans tutarlılığı, damga sonrası erasure uygulanmasının kanıtı.
- **Öneri:** İzole ortamda gerçek damga ile kontrollü drill (veri üretimden çıkmadan), ölçülmüş RTO/RPO'nun tek bir belgede yayımlanması. · **Efor:** M · **Üretim ilgisi:** unknown.

### F-28 — Gateway `/actuator/prometheus` herkese açık
- **Kategori:** Bilgi ifşası (CWE-200) · **Önem:** Düşük · **Öncelik:** P2 · **Güven:** Orta-Yüksek · **Durum:** Doğrulandı (config; canlı denenmedi)
- **Kanıt:** gateway `application.yml:232` `health,info,prometheus`; `ActuatorPublicSurfaceWebFilter.java:33` yalnızca `info/env/configprops`'u engelliyor; `docker/caddy/Caddyfile:63` aynı liste, geri kalanı gateway'e proxy. Prometheus zaten içeriden kazıyor (`prometheus.yml:50`).
- **Etki:** Rota ID'leri, istek/durum sayıları, rate-limit metrikleri, JVM iç bilgileri. **Düzeltme:** Caddy'de `/actuator/*` (health hariç) engelle. **Kabul:** `curl https://$API/actuator/prometheus` → 404. · **Efor:** S

### F-29 — Konteyner sertleştirme boşlukları
- **Kategori:** Altyapı · **Önem:** Düşük · **Öncelik:** P2 · **Güven:** Yüksek · **Durum:** Doğrulandı (config)
- **Kanıt:** Üretimde yalnızca Caddy 80/443 herkese açık — **doğru**. Ancak: promtail `/var/run/docker.sock` bağlıyor (`docker-compose.yml:434`; `:ro` API erişimini sınırlamaz); yalnızca Java servislerinde `cap_drop: ALL` + `no-new-privileges` (web nginx `USER` yok, caddy, postgres, redis, kafka, minio, exporter'lar yok); hiçbir servis `read_only` değil; `docker-compose.restored-application-verification.yml` 10 Postgres, redis, kafka, minio ve uygulamaları 0.0.0.0'a bağlıyor (Docker UFW'yi atlar → herkese açık VM'de koşulursa veritabanları açığa çıkar); `backup-minio.sh:77` şifrelenmeden önce düz metin medya aynasına `chmod -R a+rwX`.
- **Düzeltme:** Loopback bind'ler, cap_drop/no-new-privileges her yerde, docker.sock yerine dosya tabanlı log toplama, `chmod` kaldır. **Efor:** S–M

### F-30 — Pazarlama sitesi kontrast ve dil uyumsuzluğu
- **Kategori:** Erişilebilirlik (WCAG 2.2 AA 1.4.3, 3.1.1/3.1.2) · **Önem:** Düşük · **Öncelik:** P2 · **Güven:** Yüksek · **Durum:** Doğrulandı (axe, 7 sayfa × masaüstü/mobil)
- **Kanıt:** `.kicker` 4.43:1, roadmap rozetleri 4.19:1, `.footer-bottom` 3.23:1 (`web/marketing/styles.css:2,8` ve footer kuralları) — `evidence/screenshots/marketing-*-contrast-*.png`; `privacy/index.html` `lang="tr"` ama gövde İngilizce; `index.html:296` TR sayfada İngilizce footer bağlantıları; `index.html:245` JS'siz yedek metin “yalnızca e-posta” derken form ad soyad istiyor.
- **Düzeltme:** Küçük metin için `--primary-2 #0847c3`, footer ~`#5b677a`; `lang` düzelt; footer'a `data-i18n`. **Kabul:** axe `color-contrast` 0 ihlal. · **Efor:** S
- Not: Web SPA kimlik doğrulama sayfalarında 0 axe ihlali (1280/375) — güçlü alan.

### F-31 — Oyunlaştırma/moderasyon kötüye kullanım kontrolleri
- **Kategori:** Ürün/kötüye kullanım · **Önem:** Orta (özellik etkinse) · **Öncelik:** P3 · **Güven:** Orta · **Durum:** Şüpheli (iş kuralları kabul ediyor olabilir; üretimde etkinliği bilinmiyor)
- **Kanıt:** `gamification/…/GamificationApplicationService.java:130-152` her AVAILABLE doğrulama/claim'de iki tarafa puan; çift/gün sınırı yok, iptal geri almıyor; liderlik tablosu ham UUID'leri gösteriyor (`LeaderboardEntryResponse.java:7-10`). `moderation/…/ModerationApplicationService.java:100-117` rapor hedef varlığı/kendini raporlama/günlük kota yok; `findByStatusOrderByOpenedAtDesc` sınırsız (`ModerationCaseJpaRepository.java:18`); `:160-205` moderatör kendi içeriğine ait vakayı karara bağlayabiliyor. Önceki denetim #3/#8/#13 kısmen hâlâ geçerli.
- **Düzeltme:** Günlük kazanç tavanı, çift çeşitliliği, iptalde geri alma; rapor kotası + hedef doğrulama + sayfalama; görevler ayrılığı. · **Efor:** M

### F-32 — Bildirim outbox'ında zehirli payload; dead-letter kurtarma yolu yok
- **Önem:** Düşük · **Öncelik:** P3 · **Durum:** Doğrulandı (kod) · `NotificationOutboxRelay.java:108-121,186-191` — `readPayload` Phase 1'de try dışında atıyor → tüm `@Transactional` parti geri alınır (önceki satırlar Kafka'ya zaten gitmiş → her saniye yeniden gönderim), `failure_count` artmıyor; desen 8 servisin relay'inde. `scripts/outbox-deadletter-recovery.sh:62,148` `notification`'ı desteklemiyor; notification DB'de `acknowledged_deadletter`/`outbox_recovery_audit` yok. Tetikleyici bozuk bir satır gerektirir. **Düzeltme:** `toEnvelope`'u try içine al + `recordFailure`; kurtarma migration'ı ve betik girdisi. · **Efor:** S–M

### F-33 — Waitlist eşzamanlı gönderimde token rotasyonu
- **Önem:** Düşük · **Öncelik:** P3 · **Durum:** Şüpheli (kod) · `WaitlistApplicationService.java:229-257`, `JdbcWaitlistInterestRepository.java:126-150` — `UPDATE … WHERE email_hash=? AND status='PENDING'` `verification_sent_at`/`resend_count` üzerinde CAS değil; A yavaş gönderirken B token'ları döndürür → A'nın e-postasındaki onay/çıkış linkleri ölür; `sent_at` null iken `resend_count` artmıyor. Yalnızca Redis rate limiter sınırlıyor (`RedisWaitlistRateLimiter.java:28-38` INCR/EXPIRE atomik değil — TTL'siz anahtar kalabilir, doğrulanmadı). **Düzeltme:** CAS + yeni satır için cooldown penceresi. **Kabul:** aynı e-posta için iki paralel gönderim → tek geçerli token seti, tek e-posta.

### F-34 — Waitlist admin sayfalama/CSV ayrıntıları
- **Önem:** Düşük · **Öncelik:** P3 · **Durum:** Doğrulandı (kod) · `JdbcWaitlistInterestRepository.java:214-243` `safePage*safeSize` int taşması → negatif OFFSET → 500; `ORDER BY created_at DESC` bağ kırıcı yok; sayım ve sayfa ayrı sorgular. CSV formül enjeksiyonu nötralizasyonu **doğru** (`WaitlistCsv.java:51-62`), ama BOM yok (Excel'de Türkçe karakterler bozulur), export sınırsız ve bellekte, filtre `confirmed_at` değil `created_at`. Eski null `full_name`: CSV'de `""`, admin listede “Ad belirtilmemiş/Name not provided”, Slack'te “Ad belirtilmemiş” — **tutarlı ve doğru**. **Düzeltme:** long offset + sayfa tavanı, `created_at DESC, id DESC`, BOM, akışlı export.

### F-35 — Eski doğrulama token'ları çalışmaya devam ediyor
- **Önem:** Düşük · **Öncelik:** P3 · **Durum:** Doğrulandı · `AuthApplicationService.java:206-208` hesap zaten doğrulanmışsa süre kontrolünden önce kullanıcıyı (id, e-posta, roller) döndürüyor; token hash'i temizlenmiyor (`AuthUser.verifyEmail`/`AuthUser.java:144`). Web `VerifyEmailPage.tsx:30-58` `lang` farklıysa API'yi iki kez çağırıyor (üretim build'inde gözlendi) — backend idempotent olduğu için bugün zararsız. **Düzeltme:** Doğrulamada hash'i temizle, gövdesiz 204; ön yüzde ref ile tek çağrı.

### F-36 — 72 bayttan uzun parolalar
- **Önem:** Düşük · **Öncelik:** P3 · **Durum:** Şüpheli (Spring Security 6.5 `BCryptPasswordEncoder` davranışı çalıştırılmadı) · `PasswordPolicy.java:15` 100 karaktere izin; `BCryptPasswordHasher.java:12` varsayılan encoder > 72 bayt'ta `IllegalArgumentException` → `GlobalExceptionHandler.java:111` 500. Türkçe çok baytlı karakterlerle daha kısa parolalar da etkilenir. **Düzeltme:** Politikayı 72 UTF-8 bayt ile sınırla. **Kabul:** 90 baytlık parola → 400 `WEAK_PASSWORD`.

### F-37 — Doğrulanmamış `X-Correlation-Id`
- **Önem:** Düşük · **Öncelik:** P3 · **Durum:** Doğrulandı (kod) · `gw/infrastructure/web/CorrelationIdGlobalFilter.java:28-37` ve servis filtreleri (ör. `media/…/CorrelationIdFilter.java:25-27`) istemci değerini uzunluk/karakter kontrolü olmadan alıp yanıta yansıtıyor ve MDC'ye koyuyor; loglar düz metin desen (`logging.pattern.level … correlationId=%X{correlationId}`). CR/LF HTTP katmanında reddedilir; ama boşluk/köşeli parantezle sahte alanlar, aşırı uzun değerler mümkün (önceki denetim #15'in kısmen geçerli hâli). **Düzeltme:** `^[A-Za-z0-9-]{1,64}$` değilse yeni UUID üret.

### F-38 — Küçük domain kusurları (parking/media)
- **Önem:** Düşük · **Öncelik:** P3 · **Durum:** Doğrulandı (kod)
  - `V17__parking_session_stale_handling.sql:5,9` `last_confirmed_at` TIMESTAMP (tz'siz); backfill oturum saat dilimine bağlı (Europe/Istanbul ise 3 sa kayma).
  - `/spots/nearby` (`ParkingController.java:113-123`, `SearchNearbyQuery.java:9-15`) lat/lng aralık kontrolü yok (public explore doğru doğruluyor); NaN → 500 ve ham değer arama loguna.
  - Kimlikli `/facilities/nearby` limit 100/50 km'de N+1 (`MunicipalFacilityQueryService.java:147`, `MunicipalFacilityController.java:42-45`).
  - Medya `existsByChecksum` global (`MediaApplicationService.java:184`) → varlık kehaneti, silme/erasure sonrası yeniden yükleme engeli (önceki denetim #16 hâlâ geçerli).
  - `media application.yml:185-186` MinIO kimlik bilgisi yoksa `minioadmin` fallback'i, başlatma koruması yok.
  - Analytics `@Version` çakışmasında 2 yeniden denemeden sonra olay DLT'ye → metrik kaybı (Şüpheli, `AnalyticsApplicationService.java:193-221`).

### F-39 — Tarayıcı/CSP/önbellek hijyeni
- **Önem:** Düşük · **Öncelik:** P3 · **Durum:** Doğrulandı (kod)
  - `web/marketing/.htaccess:11` `script-src 'unsafe-inline'` gereksiz (tek inline script JSON-LD); HSTS yok; `frame-ancestors 'self'`. Hostinger'ın gerçekte sunduğu başlıklar doğrulanmadı.
  - `frontend/apps/web/nginx.conf:9` `connect-src … https: http://localhost:*` — Caddy CSP'si (`Caddyfile:29`, sıkı) bunu arkada değiştiriyor; Caddy'siz dağıtımda gevşek politika.
  - `public/sw.js` sabit `parkio-app-shell-v1` → hash'li `/assets/` sonsuza dek birikir; manifest cache-first.
  - `RegisterPage.tsx:112` `/check-email?email=<adres>`; doğrulama/sıfırlama token'ları adres çubuğunda kalıyor; auth sayfaları `images.unsplash.com`'dan görsel yüklüyor (kullanıcı IP'si üçüncü tarafa).
  - `vite.config.ts:6` hata raporlama açılırsa `hidden` source map'ler `dist`'e yazılıp nginx tarafından sunulur (bugün her iki bake profilinde `disabled`, 0 `.map` — gizli risk).

### F-40 — Actions pinleme ve dispatch girdisi enterpolasyonu
- **Önem:** Düşük · **Öncelik:** P3 · **Durum:** Doğrulandı (kod) · Tüm action'lar tag ile (üçüncü taraf: `bridgecrewio/checkov-action@v12`, `terraform-linters/setup-tflint@v4`, `pnpm/action-setup@v4`, `docker://zricethezav/gitleaks:v8.28.0`). `invite-production-deploy.yml:707` (`manifest_artifact`, üretim self-hosted runner'ında), `frontend-real-e2e.yml:61-62`, `shared-staging-verification.yml:48-49` girdileri `run:` içine doğrudan enterpole ediyor; yalnızca yazma yetkilileri dispatch edebilir ve invite-production'da ortam kapısı var (önceki denetim #25 hâlâ geçerli). **Düzeltme:** SHA pinleme, girdileri `env:` üzerinden geçirme.

---

## Z. Önceki denetimin (2026-07-04) bu revizyondaki durumu

| Önceki # | Konu | Bu revizyonda |
|---|---|---|
| 1 | Gateway yol kanonikleştirme | **Düzeltilmiş** — `PathCanonicalization.java` `//`, `..`, kodlanmış traversal, ters bölüyü reddediyor |
| 2 | Atomik olmayan inbox dedup | **Düzeltilmiş** — `InboxEventRepositoryAdapter` `insertIfAbsent(...) > 0` |
| 4 | AI doğrulama fail-open | **Düzeltilmiş** — varsayılan sınıflandırıcı UNCERTAIN → PENDING_REVIEW |
| 5/6 | Tek paylaşılan sır, kimliksiz Kafka | **Hâlâ geçerli** → F-16 |
| 7/20 | Konum logları saklama | **Hâlâ geçerli** → F-17 |
| 9 | Rate limiter Redis kesintisinde fail-open, e-posta bazlı kilit | Gateway limiter fail-open (belgelenmiş SCG davranışı, test edilmedi); kilit → F-15 |
| 11 | Hesap enumeration | **Kısmen geçerli** → F-14 |
| 12 | Redis kimlik doğrulaması yok | **Azaltılmış** — hosted-beta preflight `REDIS_PASSWORD`'ü zorunlu kılıyor (`preflight-hosted-beta.sh:271`); invite-production preflight'ında kontrol bulunamadı (render betiği üzerinden sağlanıyor olabilir — doğrulanmadı) |
| 13 | Moderatör görevler ayrılığı | **Hâlâ geçerli** → F-31 |
| 15 | Correlation-ID log sahteciliği | **Kısmen geçerli** → F-37 |
| 16 | Global medya checksum | **Hâlâ geçerli** → F-38 |
| 24/— | CSP yalnızca Caddy katmanında / ikon fontları | Caddy CSP sıkı; nginx fallback gevşek → F-39; ikon hatası bu denetimde gözlenmedi |
| 25 | Dispatch girdisi enjeksiyonu | **Hâlâ geçerli** → F-40 |
| 26 | `@Transactional` içinde senkron e-posta | **Hâlâ geçerli** (zamanlama kehaneti olarak F-14'te) |
| Diğerleri (3, 8, 10, 14, 17–19, 21–23) | — | Bu denetimde yeniden doğrulanmadı (kapsam/zaman) — COVERAGE-MATRIX'e bakın |
