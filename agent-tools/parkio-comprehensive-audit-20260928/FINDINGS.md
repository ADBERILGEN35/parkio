# FINDINGS: Parkio yeniden denetimi (temel çizgi `2877ec81`, 2026-09-28)

## Okuma rehberi

- **Kimlik numaraları:** F-xx önceki denetimdeki (`aa865a25`) numaralarla aynıdır. N-xx bu denetimde bulunan yeni bulgulardır.
- **Durum değerleri:**
  - *Düzeltildi*: Kod ve çalıştırılan testlerle doğrulandı.
  - *Kısmen*: Kök nedenin bir kısmı düzeltildi.
  - *Açık*: Kod değişmedi veya kusur yeniden üretildi.
  - *Şüpheli* / *Doğrulama boşluğu*: Önceki denetimdeki tanımlarıyla aynı.
- **Değişmemiş kod:** Kodu değişmemiş bulgular yeniden çalıştırılmadı (amaçsız tekrar yapılmadı). Bunlar için `git diff aa865a25 2877ec81 -- <yol>` farkının boş olduğu kontrol edildi ve 2026-09-24 kanıtı geçerli sayıldı.
- **Yol kısaltmaları:** `gw/`, `auth/` ve `parking/` kısaltmaları önceki raporla aynıdır.
- **Üretim ilgisi:** Canlı ortam gözlenmedi. Hiçbir bulgu için “observed” verilmedi.

## A. Önceki bulguların güncel durumu

| ID | Başlık (kısa) | Önceki önem | Güncel durum | Kanıt |
|---|---|---|---|---|
| F-01 | Anonim HEAD ile waitlist admin export | Orta | **Düzeltildi** | Yeniden üretim testi: 6 yöntem → 401, export çağrılmadı (`evidence/f01-gateway-recheck/`). Pin `f9710aa0` bu kodu içeriyor. |
| F-02 | MinIO hatasında COMPLETE + offsite | Yüksek | **Düzeltildi** | `backup-common.sh` `allow_complete(... minio_ok)`, kısmi MinIO siliniyor, budama başarıya bağlı. `test-backup-complete-gate.sh` gerçek betikleri stub'larla çalıştırıyor: **40/40 PASS** (kısmi mirror, seal, DB, ledger, SHA256SUMS, kesinti, offsite hatası dahil). CI'a bağlı (`backup-restore-drill.yml:154`, `observability-validation.yml:259`). |
| F-03 | DR restore bütünlük/ledger kontrolü yok | Yüksek | **Güvenlik kusuru düzeltildi, iki yeni sorun doğdu** → **N-01**, **N-02** | `restore-safe-preflight.sh`: decrypt öncesi COMPLETE, SHA256SUMS, manifest başarısı, ledger ve cutoff kontrolü. `test-restore-safe-preflight.sh` **25/25 PASS**. Ancak üretim restore'u koşulsuz engelli (N-02) ve tek bir CLI bayrağıyla açılabiliyor (N-01). |
| F-04 | Üretim setinde Alertmanager kapalı | Yüksek | **Açık** | `docker-compose.azure-hosted-beta.yml:251-252` değişmedi. |
| F-05 | Deploy guard çalışmıyor / atlatılabiliyor | Yüksek | **Büyük ölçüde düzeltildi**, Düşük kalıntı → F-05R | Betik 100755 ve `bash` ile çağrılıyor. Compose modeli render ediliyor, seçilen web imajının bundle'ı inceleniyor ve doğrulanan digest son `-f` ile bağlanıyor (`pull_policy: never`). `--build`/`--pull` reddediliyor. Break-glass yalnızca açık token ile. Test 116/116 (Part A; Docker gerektiren B/C yerelde atlandı, CI'da `PARKIO_GUARD_TEST_REQUIRE_DOCKER=1` ile zorunlu). |
| F-06 | CI rollback önceki artifact'ı indiremiyor | Orta | **Açık** | `hosted-beta-deploy.yml:201-203` ve `invite-production-deploy.yml:736-738` değişmedi (`run-id`/`github-token` yok). |
| F-07 | Zamanlanmış workflow'lar / Dependabot bayat master | Orta | **Kısmen** → F-07R | Dağıtıcı (#117) 10 workflow'u `--ref api` ile başlatıyor; api `backend-integration` 09-26 ve 09-27'de başarılı. **Dependabot hâlâ master'ı hedefliyor** (`target-branch` 0/15). Master'daki eski cron'lu kopyalar çalışmaya ve **başarısız olmaya** devam ediyor. |
| F-08 | CodeQL 4 “high” uyarı, içerik bilinmiyor | — | **Büyük ölçüde triage edildi** → F-08 (güncel) | Yerel CodeQL 2.27.1: JS/TS 5 + Java 1 high; hepsi yanlış pozitif ya da telafi edici kontrollü. |
| F-09 | Slack worker crash-loop, sınırsız tekrar | Orta | **Açık, yeniden üretildi** | `2877ec81`'de aynı çıktı: 3 çökme, 3 gönderim, `attempts=0` (`evidence/f09-slack-worker/`). |
| F-10 | Erasure ack işlem içinde, kısa retry | Orta | **Açık** | 6 servisin `AccountErasureHandler`'ı değişmedi. PR #119 (taslak) yalnızca auth tarafında kalıcı kaydı ele alıyor. |
| F-11 | İmaj kökeni: 6 pinsiz servis, belgesiz pinler | Orta | **Kısmen** | Parking pini artık kaynak ve config ile belgeli. Media pini hâlâ belgesiz. 6 servis hâlâ host'ta build ediliyor. İmza doğrulaması yok. |
| F-12 | Deploy profilleri sapması | Orta | **Açık**, N-02 ile ağırlaştı | `deploy-common.sh:116` hâlâ auth/web pinlerini ve `auth-registration-env`'i içermiyor. DR runbook adım 3'te yine farklı bir set var. |
| F-13 | Yetki iptali mevcut token'larda uygulanmıyor | Orta | **Kısmen** | Waitlist filtresi artık epoch ve durum kontrol ediyor (düzeldi). `AdminApplicationService.revokeRole`/`revokeAllSessions`/`revokeSession` hâlâ `bumpSessionEpoch()` çağırmıyor (auth değişmedi). |
| F-14 | Hesap varlığı sızıntıları | Orta | **Açık** | auth-service `src/main` değişmedi. |
| F-15 | E-posta bazlı kalıcı kilit | Orta | **Açık** | Değişmedi. |
| F-16 | İç güven modeli (tek sır, başlıktan rol, Kafka PLAINTEXT) | Orta | **Açık** | Değişmedi. |
| F-17 | Konum logları süresiz | Orta | **Açık** | `RetentionCleanupJob` hâlâ yalnızca outbox/inbox temizliyor. |
| F-18 | Waitlist rıza sürümü yok | Orta | **Açık** | Gateway waitlist başvuru kodu değişmedi. |
| F-19 | Pazarlama formu her 503'te “kaydedildi” | Orta | **Açık** | `web/marketing` değişmedi. |
| F-20 | Harita hatasında boş harita, retry yok | Orta | **Açık** | `frontend/apps/web/src` değişmedi. |
| F-21 | Reset sayfası `?lang=` parametresini yok sayıyor | Orta | **Açık** | Değişmedi. |
| F-22 | Belediye doluluğu “LIVE” anlamı | Orta | **Kısmen** | İSPARK kapalı veya açıklığı bilinmeyen otoparklarda doluluk artık gizleniyor (#111; HTTP testi ve politika testi var). İZUM küçülme eskalasyonu, `fetchedAt`'e dayalı LIVE ve İZUM `status` alanı hâlâ açık. |
| F-23 | Besleme boyut/süre sınırı yok | Orta | **Açık** | İstemciler değişmedi. |
| F-24 | DLT redrive yanlış topic | Orta | **Açık** | `tools/dlt-redrive` değişmedi. |
| F-25 | NR/relay durumu yedeklenmiyor | Orta | **Açık** | #101–#104 HOLD. |
| F-26 | CI yanlış kırmızı/yeşil | Orta | **Büyük ölçüde düzeltildi**, Düşük kalıntı → F-26R | slack_biz base düzeltildi ve api HEAD'de yeşil. mobile-v2 doctor bloklayıcı. Guard ve COMPLETE testleri CI'da. Fail-closed testi artık uçtan uca. |
| F-27 | Kurtarma kanıtı sentetik, RPO/RTO çelişkili | Orta | **Açık**, N-02 ile ağırlaştı | Üretim restore'u engelli olduğundan belgedeki RTO (1–2 sa) ölçülemez ve geçersiz. |
| F-28 | `/actuator/prometheus` herkese açık | Düşük | **Açık** | Caddyfile ve `application.yml:232` değişmedi. |
| F-29 | Konteyner sertleştirme | Düşük | **Açık** | Değişmedi. |
| F-30 | Pazarlama kontrast/dil | Düşük | **Açık** | Değişmedi. |
| F-31 | Oyunlaştırma/moderasyon kötüye kullanımı | Orta (koşullu) | **Açık (Şüpheli)** | Değişmedi. |
| F-32 | Bildirim outbox zehirli payload | Düşük | **Açık** | Değişmedi. |
| F-33 | Waitlist token yarışı | Düşük | **Açık (Şüpheli)** | Değişmedi. |
| F-34 | Waitlist sayfalama/CSV | Düşük | **Açık** | Repository değişmedi. |
| F-35 | Eski doğrulama token'ları | Düşük | **Açık** | Değişmedi. |
| F-36 | 72 bayt parola | Düşük | **Açık (Şüpheli)** | Değişmedi. |
| F-37 | Correlation-ID doğrulanmıyor | Düşük | **Açık** | Değişmedi. |
| F-38 | Küçük domain kusurları | Düşük | **Açık** | Değişmedi. |
| F-39 | Tarayıcı/CSP/SW hijyeni | Düşük | **Açık** | Değişmedi. |
| F-40 | Actions tag pinleme, girdi enterpolasyonu | Düşük | **Açık** | 139 `uses:` satırının hiçbiri SHA ile pinli değil. |

**Özet:**
- Tamamen düzeltilen: 2 (F-01, F-02).
- Büyük ölçüde düzeltilen: 2 (F-05, F-26; Düşük kalıntılı).
- Güvenlik kusuru düzeltilen ama yerine yeni risk doğan: 1 (F-03 → N-01, N-02).
- Kısmen düzeltilen: 5 (F-07, F-11, F-13, F-22, F-08 triage).
- Açık kalan: 30.

## B. Güncel bulgu listesi (`2877ec81`)

| ID | Başlık | Kategori | Önem | Öncelik | Durum |
|---|---|---|---|---|---|
| **N-01** | Üretim restore koruması tek bir CLI bayrağıyla (`--isolated-fixture`) açılıyor; hedefin izole olduğu doğrulanmıyor | Koruma mekanizması hatası | **Yüksek** | **P0** | Doğrulandı (yeniden üretildi) |
| **N-02** | Desteklenen bir üretim kurtarma yolu yok; DR runbook'undaki tüm restore adımları reddediliyor, runbook güncellenmemiş | Operasyonel/DR boşluğu | **Yüksek** | **P0** | Doğrulandı (kod + test) |
| F-04 | Üretim setinde Alertmanager kapalı | Operasyonel boşluk | **Yüksek** | P0 (doğrula) | Doğrulandı (config) / canlı bilinmiyor |
| F-09 | Slack worker crash-loop | Veri/olay tutarlılığı | Orta | P1 | Doğrulandı (yeniden üretildi) |
| F-06 | CI rollback artifact indiremiyor | Operasyonel kusur | Orta | P1 | Doğrulandı (kod) |
| F-10 | Erasure ack işlem içinde | Gizlilik/veri bütünlüğü | Orta | P1 | Doğrulandı (kod) |
| F-11 | Pinsiz servisler, belgesiz media pini, imza doğrulaması yok | Deploy tekrarlanabilirliği | Orta | P1 | Doğrulandı |
| F-12 | Deploy/DR compose seti sapması | Operasyonel risk | Orta | P1 | Doğrulandı |
| F-27 | Kurtarma kanıtı sentetik; RTO iddiası geçersiz | Operasyonel boşluk | Orta | P1 | Doğrulama boşluğu |
| F-07R | Dependabot master'ı hedefliyor; master'daki eski cron'lar sürekli kırmızı | CI/CD riski | Orta | P1 | Doğrulandı |
| F-13 | Auth rol iptali ve admin oturum iptali epoch artırmıyor | Güvenlik zayıflığı | Orta | P2 | Doğrulandı (kod) |
| F-14 | Hesap varlığı sızıntıları | Güvenlik zayıflığı | Orta | P2 | Doğrulandı (kod) |
| F-15 | E-posta bazlı kalıcı kilit | Güvenlik zayıflığı | Orta | P2 | Doğrulandı (kod) |
| F-16 | İç güven modeli | Mimari risk | Orta | P2 | Doğrulandı |
| F-17 | Konum logları süresiz | Gizlilik | Orta | P2 | Doğrulandı |
| F-18 | Rıza kanıtı eksik | Gizlilik | Orta | P2 | Doğrulandı |
| F-19 | Waitlist 503 → “kaydedildi” | Yanıltıcı mesaj | Orta | P2 | Doğrulandı |
| F-20 | Harita hatası / retry yok | UX | Orta | P2 | Doğrulandı |
| F-21 | Reset `?lang=` | i18n | Orta | P2 | Doğrulandı |
| F-22 | İZUM doluluk semantiği (kalan kısım) | İşlevsel doğruluk | Orta | P2 | Doğrulandı (kod) |
| F-23 | Besleme boyut/süre sınırı yok | DoS | Orta | P2 | Doğrulandı |
| F-24 | DLT redrive yanlış topic | Olay tutarlılığı | Orta | P2 | Doğrulandı |
| F-25 | NR/relay durumu yedeklenmiyor | DR | Orta | P2 | Doğrulandı |
| F-31 | Oyunlaştırma/moderasyon kötüye kullanımı | Ürün | Orta (koşullu) | P3 | Şüpheli |
| F-05R | Guard bundle kontrolü hâlâ kara liste (`REPLACE_ME_maptiler_public_key` yakalanmıyor) | Sürüm güvenliği | Düşük | P3 | Doğrulandı (kod) |
| F-26R | slack_biz compose kontrolü PR #44'te api'yi kendisiyle kıyaslıyor; process substitution hatayı maskeliyor | Test kalitesi | Düşük | P3 | Doğrulandı (kod) |
| F-08 | CodeQL “4 new high” uyarısı: yerel tarama 6 high buldu, hepsi yanlış pozitif ya da telafi edici kontrolle kapatılmış; GitHub'daki 4 uyarıyla birebir eşleşme doğrulanamadı | Doğrulama boşluğu (kapanmaya yakın) | Bilgi | P2 (UI'da triage/kapat) | Kısmen doğrulandı |
| N-03 | DR bağımlılığı: MinIO/mc (ve uygulama) imajları özel GHCR'de; host'ta registry kimlik bilgisi gerekiyor ama runbook'ta yok | Operasyonel boşluk | Düşük | P2 | Doğrulandı (config) |
| F-28 | `/actuator/prometheus` herkese açık | Bilgi ifşası | Düşük | P2 | Doğrulandı (config) |
| F-29 | Konteyner sertleştirme | Altyapı | Düşük | P2 | Doğrulandı |
| F-30 | Pazarlama kontrast/dil | Erişilebilirlik | Düşük | P2 | Doğrulandı |
| F-32 – F-40 | (önceki rapordaki gibi) | — | Düşük | P3 | Açık |

**Güncel önem sayıları** (Açık + Kısmen + Yeni; F-08 hariç; Düzeltildi olanlar hariç):
- **Kritik:** 0
- **Yüksek:** 3 (N-01, N-02, F-04)
- **Orta:** 21. Bunlar F-06, F-07R, F-09–F-25 (bu aralıkta 17 bulgu), F-27 ve F-31. F-31 şüphelidir.
- **Düşük:** 15. Bunlar F-05R, F-26R, N-03, F-28–F-30 ve F-32–F-40. F-33 ve F-36 şüphelidir.
- **Toplam:** 39 bulgu + 1 kısmi doğrulama boşluğu (F-08).
- **Önceki denetimle karşılaştırma** (4 Yüksek / 23 Orta / 12 Düşük): Yüksek sayısı aynı ama içerik değişti. F-02, F-03 ve F-05 kapandı; yerlerine N-01 ve N-02 geldi.

---

## C. Ayrıntılı bulgular: yeni ve durumu değişenler

### N-01: Üretim restore koruması tek bir CLI bayrağıyla açılıyor; “izolasyon” doğrulanmıyor

**Sınıflandırma**
- **Kategori:** Koruma mekanizması hatası (CWE-693). Güvenlik kararı istemcinin/operatörün beyanına dayanıyor (CWE-807).
- **Önem:** Yüksek. **Öncelik:** P0. **Güven:** Yüksek. **Durum:** Doğrulandı (sentetik yeniden üretim).
- **Etkilenen temel çizgi:** A (`2877ec81`). **Bileşen:** `scripts/restore-hosted-beta.sh`, `scripts/restore-database.sh`, `scripts/lib/restore-safe-preflight.sh`.

**Dosya/satır**
- `scripts/lib/restore-safe-preflight.sh:62-76`: `parkio_restore_accept_isolated_fixture`. `PARKIO_RESTORE_ISOLATED_FIXTURE=1` iken bilet verilmemişse `parkio_restore_issue_isolated_ticket` bileti **kendisi üretiyor**.
- `:10-33`: `parkio_restore_isolated_fixture_ok` yalnızca bilet dosyasının içeriğini ve damga yolunu kontrol ediyor.
- `:171-202`: `refuse_unverified_production`, `refuse_unsupported_production_scope` ve `refuse_standalone_database` bu kontrol geçince hepsi `return 0`.
- `scripts/restore-hosted-beta.sh:46`: `--isolated-fixture) PARKIO_RESTORE_ISOLATED_FIXTURE=1`.
- `scripts/restore-database.sh:52`: aynı bayrak.
- `scripts/restore-database.sh:122-137`: container adları sabit `parkio-postgres-<svc>`, yani üretim adları.
- Betik başlığı “Env flags alone do not bypass” diyor. Bu doğru, ama **CLI bayrağı** atlatıyor.

**Gözlenen**

Aynı sentetik şifreli damga, aynı `--recovery-cutoff` ve `PARKIO_DEPLOYMENT_PROFILE=hosted-beta` ile:

| Deneme | Komut | Sonuç |
|---|---|---|
| A | `restore-hosted-beta.sh --manifest … --yes --only databases --recovery-cutoff …` | `exit=3`, 0 yıkıcı komut |
| B | A ile aynı komut + `--isolated-fixture` | `exit=0`, **11 `PSQL_APPLY`** (`psql -v ON_ERROR_STOP=1 -U parkio_auth -d parkio_auth` …). Hedef varsayılan `parkio-postgres-*` container'ları. |

**Beklenen:** “İzole” kipi, hedefin üretim olmadığını kanıtlamalı. Örnekler:
- Container ve proje adı ön eki izole bir desenle eşleşmeli.
- Deployment profili üretim profili olmamalı.
- Bilet dışarıdan, imzalı veya hedef veritabanında önceden oluşturulmuş bir işaretle bağlanmalı.
- Bu kontroller geçmezse reddedilmeli.

**Kök neden:** İzolasyon, koşulan betiğin kendi ürettiği bir dosyayla “kanıtlanıyor”. Hedef ortamın hiçbir özelliği kontrol edilmiyor.

**Tetikleyici/ön koşul:** Üretim host'unda shell erişimi olan bir operatör (yetkili kişi). Kötü niyet gerekmez. N-02 nedeniyle bir olay sırasında desteklenen tek “çalışan” komut bu bayrakla elde ediliyor. Bayrak `--help` çıktısında ve betik başlığında görünüyor.

**Etki**
- Asıl kaygı: Silinmiş kullanıcıların verisi geri gelebilir (KVKK/GDPR). Nedeni: Erasure kapsamı doğrulanmamış bir yedek üretim veritabanlarının üzerine yazılıyor, ki #109 tam da bunu engellemek için eklenmişti.
- Ayrıca `--only minio` gibi desteklenmeyen kapsamlarla da çalışıyor.
- Etki sınırı: Kötü niyetli bir dış saldırgan için geçerli değil; operatör hatası ve baskı altında karar senaryosu için geçerli.

**Kanıt**
- `evidence/f03-restore/audit-f03-isolated-flag.sh`: depodaki `test-restore-safe-preflight.sh` fixture kurulumunun kopyası ve iki deneme.
- `evidence/f03-restore/isolated-flag-output.txt`.
- Komut: `bash scripts/audit-f03-isolated-flag.sh` (`2877ec81` kopyasında). Stub docker/psql kullanıldı; gerçek veritabanına dokunulmadı.

**Mevcut azaltım:** Bayrak gerektiğini bilmek için betiği okumak gerekiyor. Coverage için cutoff hâlâ isteniyor.

**Asgari düzeltme**
- İzole kipte hedefin izole olduğunu zorunlu kıl: container/proje adı ön eki `PARKIO_PG_CONTAINER_PREFIX` üretim değerinden farklı olmalı, üretim profilleri reddedilmeli.
- Kendi kendine bilet üretimini kaldır; bileti yalnızca drill orkestratörü üretsin ve hedefe bağlasın.
- Üretim için ayrı ve açıkça adlandırılmış bir kurtarma kipi tasarla (N-02).

**Kabul testi:** `PARKIO_DEPLOYMENT_PROFILE` üretim değeriyken ve container adları varsayılanken `--isolated-fixture` → exit ≠0, 0 `PSQL_APPLY`. Mevcut izole drill'ler (farklı ön ekle) geçmeye devam etmeli.

**Diğer alanlar**
- **Bağımlılıklar:** N-02 ile birlikte tasarlanmalı.
- **Efor:** S–M (kontrol basit; drill çağrılarının güncellenmesi gerekiyor).
- **Açık PR örtüşmesi:** #118 (tasarım) ve #119 (auth kalıcı kayıt) bu bayrağı ele almıyor. #104 HOLD.
- **Üretim ilgisi:** plausible.

### N-02: Desteklenen bir üretim kurtarma yolu yok; runbook tutarsız

**Sınıflandırma**
- **Kategori:** Operasyonel/DR boşluğu.
- **Önem:** Yüksek. **Öncelik:** P0 (en azından belgelenmiş ve prova edilmiş bir “break-glass” prosedürü). **Güven:** Yüksek. **Durum:** Doğrulandı.

**Dosya/satır**
- `scripts/restore-hosted-beta.sh:13-15`: “Production path is BLOCKED before decrypt or apply … `--isolated-fixture` is the only supported synthetic path”.
- `restore-safe-preflight.sh:171-179`: `refuse_unverified_production` yalnızca izole kipte geçiyor. Başka hiçbir girdi kombinasyonu “verified coverage” üretmiyor (test: “equal cutoff without verified coverage stays BLOCKED”).
- `restore-database.sh:95` tekil restore'u, `:181-193` MinIO-only restore'u reddediyor.
- `docs/operations/disaster-recovery-runbook.md`:
  - `:10-13` senaryo tablosu hâlâ `restore-database.sh <service> <dump>` (tek DB), `restore-hosted-beta.sh` (tam host kaybı) ve `--only minio` diyor.
  - `:31` adım 5 hâlâ `restore-hosted-beta.sh … --recovery-cutoff … --yes`. Bu komut **exit 3** ile reddediliyor (N-01 deneme A).
  - `:16-22` RTO “1-2h restore + smoke”.
- PR #118 gövdesi: “F-03 is not closed in production”.

**Gözlenen:** Runbook'taki üç restore yolu da (tek DB bozulması, tam host kaybı, yalnızca MinIO) araç tarafından reddediliyor. Runbook bunu söylemiyor. Tek çalışan yol N-01'deki belgelenmemiş bayrak.

**Beklenen**
- Ya (a) doğrulanabilir bir erasure kapsamı kanıtıyla çalışan, desteklenen bir üretim restore kipi,
- ya da (b) runbook'ta açıkça tanımlanmış, prova edilmiş bir break-glass prosedürü: kimin onayıyla, hangi riskle ve silme işlemlerinin sonradan nasıl yeniden uygulanacağıyla.

**Kök neden:** F-03 düzeltmesi güvenli bir varsayılan seçti (“doğrulanamıyorsa reddet”), ancak doğrulama altyapısı (#118/#119, #102/#104) henüz birleşmedi. Runbook ve RTO iddiaları güncellenmedi.

**Etki:** Host kaybında veya veri bozulmasında belgelenmiş kurtarma çalışmaz. Belgedeki RTO geçersiz, gerçek RTO bilinmiyor. Operatör ya N-01 bayrağını kullanır ya da elle `psql`/`pg_restore` yapar; ikisinde de erasure yeniden uygulanmaz.

**Kanıt:** `evidence/f03-restore/test-restore-safe-preflight.txt` (25/25 PASS; üretim retleri dahil) ve N-01 deneme A.

**Asgari düzeltme**
- Kısa vadede runbook'u güncelle: engellenen yolları işaretle ve onaylı break-glass adımlarını yaz. Bu adımlar erasure ledger'ının sonradan yeniden uygulanmasını ve servislerin kapalı tutulmasını içermeli.
- Break-glass'i N-01 düzeltmesiyle ayrı ve adlandırılmış bir kip yap (ör. `--break-glass-unverified-coverage` + gerekçe + onay kaydı).
- İzole bir ortamda gerçek damga ile prova et.

**Kabul testi:** Runbook'taki her restore komutu `2877ec81`+ ile sentetik ortamda uçtan uca çalışıyor veya açık bir break-glass prosedürüne yönlendiriyor. Tarihli bir drill kaydında ölçülmüş RTO var.

**Diğer alanlar**
- **Bağımlılıklar:** N-01, F-27, #118/#119.
- **Efor:** M.
- **Açık PR örtüşmesi:** #118, #119, #102, #104 (hepsi taslak).
- **Üretim ilgisi:** plausible.

### F-04: Kanonik üretim setinde Alertmanager kapalı (değişmedi)

Tüm alanlar 2026-09-24 raporundaki gibidir (`../parkio-comprehensive-audit-20260924/FINDINGS.md#f-04`).
- **Güncel kanıt:** `docker/docker-compose.azure-hosted-beta.yml:251-252`.
- **Önceliğin P0 kalma nedeni:** F-02 düzeltmesinden sonra yedek hataları artık fail-closed. Yani COMPLETE yazılmıyor ve `BackupFailed`/`BackupStale` metrikleri doğru biçimde kırmızıya dönüyor. Ama Alertmanager çalışmıyorsa bu sinyal **kimseye ulaşmıyor**. N-03'teki GHCR kimlik bilgisi süresinin dolması gibi bir nedenle yedekler sessizce durabilir.
- **Açık PR örtüşmesi:** yok.
- **Üretim ilgisi:** unknown.

### F-07R: Dependabot master'ı hedefliyor; master'daki eski cron'lar sürekli kırmızı

**Sınıflandırma:** Kategori CI/CD riski. Önem Orta. Öncelik P1. Güven Yüksek. Durum Doğrulandı.

**Düzelen kısım:** Master'daki `scheduled-restore-drills.yml` artık izin listesindeki 10 workflow'u `gh workflow run … --ref api` ile başlatıyor.
- Örnek: api'de `backend-integration`, run 36230223306 (2026-09-26) ve 36308739381 (2026-09-27), ikisi de başarılı.

**Kalan kısım**
- **Dependabot:** `.github/dependabot.yml` 15 girdinin hiçbirinde `target-branch` yok. Açık 20 Dependabot PR'ı hâlâ master'a açık. api'ye otomatik güvenlik güncellemesi gelmiyor.
- **Master'daki eski cron'lu workflow'lar:** `security-ci`, `backend-integration` ve diğerleri hâlâ `schedule:` tetikleyicisiyle **bayat master kodunda** çalışıyor ve düzenli olarak başarısız oluyor:
  - `security-ci` schedule koşuları: 2026-08-24, 08-31, 09-07, 09-14, 09-21 → **hepsi failure** (master `e5692428`).
  - `backend-integration` schedule koşuları: 2026-09-25, 09-26, 09-27 → **failure** (master).
- **Dağıtıcının gecikmesi:** Cron'lar GitHub'da 5–6 saat gecikmeyle çalışıyor (ör. 03:17 cron'u 09:14'te).
- **Sessiz başarısızlık riski:** Dağıtılan koşular `github-actions[bot]` adına çalışıyor; başarısızlık bildiriminin bir insana ulaştığı doğrulanmadı.

**Etki:** Sürekli kırmızı olan zamanlanmış koşular gerçek bir regresyonu gizler (alarm yorgunluğu). Güvenlik güncellemeleri api'ye gelmiyor.

**Asgari düzeltme:** Master'daki bu workflow'lardan `schedule:` tetikleyicisini kaldır (dağıtıcı zaten api'yi çalıştırıyor). Dependabot'a `target-branch: api` ekle. Dağıtılan koşular için başarısızlık bildirimi kur.

**Kabul testi:** Bir hafta boyunca master'da schedule koşusu yok; api'de dispatch koşuları var. Yeni Dependabot PR'ları api tabanlı.

**Diğer alanlar:** Efor S. Üretim ilgisi not applicable.

### F-08 (güncel): CodeQL “4 new high” triage

**Sınıflandırma:** Doğrulama boşluğu → büyük ölçüde kapatıldı. Önem Bilgi. Öncelik P2 (GitHub arayüzünde uyarıları kapat).

**Yöntem**
- GitHub code-scanning API'si bu oturumda erişilebilir değil.
- CodeQL CLI 2.27.1 indirildi (`codeql-bundle-linux64`) ve `2877ec81`'in `git archive` kopyasında workflow'daki dil ve sorgu setleriyle çalıştırıldı:
  - `javascript-typescript` için `build-mode=none` ve varsayılan `javascript-code-scanning.qls`.
  - `java-kotlin` için Gradle `compileJava compileTestJava` ile veritabanı.

**Sonuç: JS/TS**
- 5 sonuç, hepsi security-severity 7.8 (“high”). Hepsi **yanlış pozitif**:
  - `js/xss-through-dom` `frontend/apps/web/src/pages/UploadPage.tsx:433,879`. `<img src={previewUrl}>`, kullanıcının kendi seçtiği dosyadan `URL.createObjectURL(file)` (`:228`) ile üretilen blob URL'si. `img src` bağlamında betik çalışmaz ve veri kullanıcının kendisinden gelir.
  - `js/incomplete-url-substring-sanitization` `scripts/validate-marketing-site.mjs:155,159,164`. Bunlar `Array.prototype.includes` ile dizide tam eşleşme kontrolü; alt dize kontrolü değil. Ayrıca yalnızca CI doğrulama betiği.
- Aynı taramada 25 GitHub Actions dosyası da analiz edildi; sonuç yok.

**Sonuç: Java/Kotlin:** 2.242/2.362 dosya tarandı; 1 sonuç (security-severity 8.8, “high”) çıktı:
- `java/spring-disabled-csrf-protection`, `services/auth-service/.../SecurityConfig.java:27` (`csrf.disable()`).
- Değerlendirme: **Fiilen yanlış pozitif (telafi edici kontrol var).**
  - Servis `SessionCreationPolicy.STATELESS` ve Bearer JWT kullanıyor.
  - Ortam çerezi taşıyan tek akışlar refresh ve logout. Bunlarda `AuthController.java:122-126, 209, 230` `validateOrigin` ile Origin allowlist uyguluyor.
  - Refresh çerezi HttpOnly, Secure, `SameSite=Strict` ve path ile sınırlı.
- Kanıt: `evidence/codeql/java-kotlin-results.txt`.

**Toplam:** Yerelde 6 “high” sonuç var (5 JS + 1 Java). **Gerçek bir güvenlik açığı bulunmadı.**

**Kalan boşluk:** GitHub'ın “yeni” saydığı 4 uyarının bu listelerle birebir eşleştiği doğrulanamadı; GitHub karşılaştırmayı master'a göre yapıyor. Önerilen adım: code scanning arayüzünde bu uyarıları “false positive” gerekçesiyle kapatmak veya düzeltmek.

### F-05R: Deploy guard bundle kontrolü hâlâ kara liste

**Sınıflandırma:** Önem Düşük. Öncelik P3. Durum Doğrulandı (kod).

**Kanıt**
- `scripts/lib/web_bundle_map_config.py:32-57`: `KNOWN_PLACEHOLDER_VALUES` 5 sabit değer ve bir `ci-web-build-security-synthetic*` ön eki.
- `docker/.env.invite-production.example` içindeki `VITE_MAPTILER_KEY="REPLACE_ME_maptiler_public_key"` değeri bu listede yok.
- `frontend/apps/web/scripts/validate-build-env.mjs` REPLACE_ME ve benzerlerini reddetmiyor.

**Etki:** Doldurulmamış örnek env ile derlenen bir web imajı guard'dan geçer ve üretimde boş harita görünür.

**Düzeltme:** `REPLACE_ME`, `CHANGE_ME`, `placeholder`, `dummy` ve `example` desenlerini build ve bundle doğrulayıcılarında reddet.

### F-26R: CI test kalitesi kalıntıları

**Sınıflandırma:** Önem Düşük. Öncelik P3. Durum Doğrulandı (kod).

**Kanıt**
- `slack-biz-relay-acceptance.yml:88-92`: base `master` olduğunda `origin/api` kullanılıyor. PR #44'ün head'i api olduğu için bu, api'yi kendisiyle kıyaslamak demek; kontrol anlamsız ama yeşil.
- `validate-compose-integration.sh:42`: `mapfile … < <(files_args …)`. Process substitution içindeki hata hâlâ `set -e`'yi tetiklemiyor.
- Legacy mobile işi `continue-on-error: true` (bilinçli advisory).

**Düzeltme:** merge-base ile kıyasla. `files_args` çıktısını önce bir değişkene alıp dönüş kodunu kontrol et.

### N-03: DR bağımlılığı olarak özel GHCR kimlik bilgisi belgelenmemiş

**Sınıflandırma:** Kategori operasyonel boşluk. Önem Düşük. Öncelik P2. Durum Doğrulandı (config).

**Kanıt**
- `docker/docker-compose.yml`: MinIO ve mc varsayılanları artık `ghcr.io/adberilgen35/parkio/{minio,mc}@sha256:…`.
- `scripts/backup-minio.sh:40` ve `scripts/lib/backup-common.sh:784`: yedeklemenin varsayılan `mc` imajı da özel GHCR'de.
- `scripts/ci/ghcr-minio-login.sh`: “private parkio/minio and parkio/mc”.
- Uygulama imaj pinleri de aynı registry'de.
- `docs/operations/disaster-recovery-runbook.md` ve `backup-runbook.md` GHCR oturumundan hiç bahsetmiyor.

**Etki**
- Yeni bir host'ta (host kaybı) veya token süresi dolduğunda imajlar çekilemez.
- Yedek cron'unda `mc` imajı önbellekte değilse MinIO yedeği başarısız olur. F-02 düzeltmesi sayesinde bu artık fail-closed, yani COMPLETE yazılmaz. Ama F-04 nedeniyle bu başarısızlık kimseye ulaşmayabilir.
- GitHub hesabı veya paket saklama süresi artık bir DR bağımlılığı.

**Düzeltme:** Runbook'a registry kimlik bilgisi saklama ve yenileme adımlarını ekle. Kritik imajları offsite yedeğe dahil et (`docker save`) veya ikincil registry kullan. Yedek öncesi `docker pull` kontrolü ve uyarı ekle.

## D. Değişmeyen açık bulgular (ayrıntı önceki raporda)

F-06, F-09–F-25, F-27–F-40 maddelerinin tüm alanları (dosya/satır, gözlenen, beklenen, kök neden, etki, düzeltme, kabul testi, efor) `../parkio-comprehensive-audit-20260924/FINDINGS.md` dosyasında olduğu gibi geçerlidir. Bu denetimde:

- **Satır numaraları:** İlgili dosyalarda `git diff aa865a25 2877ec81` boş olduğu için satır numaraları aynıdır. İstisnalar:
  - F-13'ün gateway kısmı (düzeldi).
  - F-22'de `MunicipalFacilityQueryService.java`/`PublicExploreQueryService.java` satırları kaydı; İZUM kısmının satırları (`MunicipalFacilitySyncService.java:129-148`, `IzumNormalizer.java:134-141`, `IzumRecordValidator.java:17-23`) aynı.
- **Güncellenen açık PR örtüşmeleri:**
  - F-10: #119 (auth tarafında kalıcı erasure kaydı; katılımcı servislerdeki işlem-içi ack'i değiştirmiyor).
  - F-25: #101–#104 (HOLD).
  - F-11: #56.
