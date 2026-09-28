# REMEDIATION-PLAN: Parkio (`2877ec81`, 2026-09-28)

## Genel çerçeve

**Önceki plandan kapananlar**
- P0-1: F-01 ve F-13'ün waitlist kısmı (#106/#107).
- P0-2: F-02 (#105).
- P1-2: F-05 (#108/#110).
- P1-4: F-07'nin dispatch kısmı (#117).
- P2-14: F-26'nın büyük kısmı (#116).
- P1-1: F-03 güvenlik kısmı (#109).

Bu PR'lar birleşmiş durumda. Bu denetim hepsini yeniden test etti; sonuçlar FINDINGS §A'da.

**Önceliklendirme ilkesi**
1. Felaket anında sessiz arıza ve doğrulanmamış veri yazımı
2. Kurtarılabilirlik
3. Sürüm güvenliği
4. Gizlilik ve UX
5. Sertleştirme

**Çakışma notu:** Açık taslak PR'larla (#104, #118, #119) çakışan işler işaretlidir. **Bu dallara dokunulmamalı.** Sıralama, dalların sahibiyle birlikte yapılmalı.

## P0: Sınırlı beta devam ederken hemen

| # | İş | Bulgular | Kabul kriteri | Bağımlılık | Efor | Çakışma |
|---|---|---|---|---|---|---|
| P0-A | **Canlı uyarı teslimini doğrula**, ardından Alertmanager'ı kanonik üretim setine taşı. Deploy sonrası smoke testi ve Watchdog/dead-man's switch ekle. | F-04 | Üretim seti config render'ında `alertmanager` görünüyor. Sentetik bir uyarı Slack test kanalına ulaşıyor. `BackupStale` uyarısı ateşlenebiliyor. | Host erişimi olan operatör (doğrulama adımı için) | S | Yok (`docker-compose.azure-hosted-beta.yml` hiçbir açık PR'da değişmiyor) |
| P0-B | `--isolated-fixture` hedefin izole olduğunu doğrulasın. Üretim deployment profilini ve varsayılan `parkio-postgres-*` container'larını reddetsin. Kendi kendine bilet üretimini kaldır; bileti yalnızca drill orkestratörü üretsin. | N-01 | `evidence/f03-restore/audit-f03-isolated-flag.sh` deneme B'de exit ≠0 ve 0 `PSQL_APPLY` veriyor. Mevcut izole drill'ler geçmeye devam ediyor. | — | S–M | **Var.** #118 ve #119 `scripts/test-restore-safe-preflight.sh` dosyasını değiştiriyor. #118 sahibiyle sıralanmalı (ya #118'den önce küçük bir PR, ya da #118'e rebase). |
| P0-C | DR runbook'unu güncelle. Engellenen yolları açıkça işaretle. **Onaylı bir break-glass prosedürü** yaz: kim onaylar, servisler kapalı tutulur, erasure ledger sonradan yeniden uygulanır, olay kaydı tutulur. Geçersiz RTO iddiasını kaldır. | N-02, F-27 | Runbook'taki her komut `2877ec81` üzerinde ya çalışıyor ya da açıkça break-glass prosedürüne yönlendiriyor. Prosedür izole ortamda bir kez prova edilmiş. | P0-B | S–M | #118 (tasarım belgesi) ile içerik uyumu |
| P0-D | Son yedeklerin `COMPLETE` ve `minioOk=1` olduğunu kontrol et. Host'un özel GHCR'den `mc` ve `minio` imajlarını çekebildiğini doğrula. Registry kimlik bilgisinin saklama ve yenileme adımlarını yaz. | N-03, F-02 sonrası doğrulama | Kayıt altına alınmış kontrol listesi. `docker pull` sonucu. | Operatör | S | Yok |

## P1: Geniş sürümden önce

| # | İş | Bulgular | Kabul kriteri | Efor | Çakışma |
|---|---|---|---|---|---|
| P1-1 | Slack relay: `http.client.HTTPException`'ı AMBIGUOUS olarak sınıflandır. Worker'a catch-all ekle. Lease geri alımında `attempts++`. | F-09 | `poison_worker.py` en fazla MAX_ATTEMPTS gönderim yapıyor, satır `delivery_unknown` oluyor, worker çökmüyor. | S | Yok (#104 yalnızca `deploy/civo/*`'a dokunuyor) |
| P1-2 | CI rollback: `download-artifact` adımına `run-id`, `github-token` ve `actions: read` ekle. | F-06 | Staging'de önceki koşu artifact'ıyla rollback başarılı. | S | Yok |
| P1-3 | Master'daki bayat `schedule:` tetikleyicilerini kaldır. Dependabot'a `target-branch: api` ekle. Dağıtılan koşuların başarısızlığı için bildirim kur. | F-07R | Bir hafta boyunca master'da schedule koşusu yok. Yeni Dependabot PR'ları api tabanlı. | S | Yok (master'da yalnızca dağıtıcı var) |
| P1-4 | Erasure ack'ini commit sonrasına veya outbox'a taşı. Uzun bir backoff uygula. Tombstone kontrolü ekle. Bu, 6 katılımcı servisi kapsıyor. | F-10 | Ack hata enjeksiyonu testi geçiyor. Commit hatasında ack gönderilmiyor. | M | Kavramsal olarak #119 ile ilgili (auth tarafı). Katılımcı dosyaları çakışmıyor. |
| P1-5 | Tüm 10 servis için digest pinli imajlar. Media pininin kaynağını belgele. Deploy'da imza/attestation doğrulaması yap. | F-11 | Üretim seti render'ında `build:` yok. Her pinin kaynak SHA'sı ve config ID'si kayıtlı. | M | #56 |
| P1-6 | Tek compose dosya listesi: `deploy-common.sh`, rollback ve DR runbook aynı `compose.production.files`'ı kullansın. | F-12 | Tüm yollar aynı config hash'ini üretiyor. | S–M | **Var:** #104 `deploy-common.sh`'yi değiştiriyor. Sıralama gerekli. |
| P1-7 | Erasure kapsamı doğrulanabilir bir üretim restore kipi kur. Gerçek bir damga ile izole drill yap ve RTO'yu ölç. | N-02, F-27 | Tarihli drill kaydı ve ölçülmüş RTO/RPO. | L | #118, #119, #102, #104 |
| P1-8 | CodeQL uyarılarını GitHub arayüzünde gerekçesiyle kapat (bu denetimdeki sınıflandırmaya göre). | F-08 | PR #44'te CodeQL toplu kontrolü yeşil veya uyarılar belgelenmiş olarak kapatılmış. | S | Yok |

## P2: Geniş sürüm için güvenlik, gizlilik ve UX

Önceki planın P2-1 … P2-17 maddeleri **aynen geçerli.** Tek fark P2-14'te: yalnızca F-26R kaldı. Madde listesi:

| Madde | Konu |
|---|---|
| P2-1 | F-13 auth kısmı: `revokeRole`, `revokeAllSessions` ve `revokeSession` epoch artırsın |
| P2-2 | F-14 enumeration |
| P2-3 | F-15 kilit |
| P2-4 | F-16 iç kimlik |
| P2-5 | F-17 TTL |
| P2-6 | F-18 rıza |
| P2-7 | F-19 |
| P2-8 | F-20 |
| P2-9 | F-21 |
| P2-10 | F-22 İZUM kısmı |
| P2-11 | F-23 |
| P2-12 | F-24 |
| P2-13 | F-25 (#101–#104) |
| P2-14 | F-26R |
| P2-15 | F-28 |
| P2-16 | F-29 |
| P2-17 | F-30 |

Kabul kriterleri önceki plandaki gibidir.

## P3: Sertleştirme

F-05R (placeholder desenleri), F-31–F-40 (önceki plandaki gibi).

## Önerilen çakışmasız iş akışları

| Akış | Kapsam | Dokunduğu yerler | Açık PR ile çakışma |
|---|---|---|---|
| **WS-1: Uyarı teslimi** | P0-A | `docker/docker-compose.azure-hosted-beta.yml`, `docker/prometheus`, deploy smoke betiği | Yok |
| **WS-2: Relay güvenilirliği** | P1-1, F-24 | `scripts/slack_biz/{transport,delivery,worker,store}.py`, `tools/dlt-redrive` | Yok |
| **WS-3: Restore izolasyonu ve break-glass** | P0-B, P0-C, P1-7 | `scripts/lib/restore-safe-preflight.sh`, `restore-*.sh`, runbook | **Var:** #118/#119 (test dosyası). Sahibiyle sıralanmalı. |
| **WS-4: CI hijyeni** | P1-2, P1-3, P1-8, F-40 | `.github/workflows/*`, `dependabot.yml`, master dağıtıcısı | Düşük (#118/#119 `restore-drill-01-procedure.yml`'e dokunuyor) |
| **WS-5: Auth sertleştirme** | P2-1, P2-2, P2-3, F-36 | `auth/application/admin/AdminApplicationService.java`, `AuthApplicationService.java`, `RedisLoginFailureTracker.java` | Düşük (#119 `AccountErasureApplicationService.java` ve `GlobalExceptionHandler.java`'ya dokunuyor; farklı dosyalar) |
| **WS-6: Deploy tekrarlanabilirliği** | P1-5, P1-6 | compose dosyaları, `deploy-common.sh`, release workflow | **Var:** #104 (`deploy-common.sh`) |
| **WS-7: Ön yüz UX/i18n/a11y** | P2-7–P2-9, P2-17 | `web/marketing`, `frontend/apps/web` | #93 (yalnızca stil) |

## Hemen paralel başlatılabilecek iki bağımsız iş

1. **WS-1 (P0-A):** Alertmanager'ı kanonik üretim setine almak ve deploy smoke testi eklemek. Canlı doğrulama adımı operatör işidir; depo değişikliği ondan bağımsız hazırlanabilir.
2. **WS-2 (P1-1):** Slack relay crash-loop düzeltmesi ve `poison_worker.py`'nin kalıcı regresyon testine dönüştürülmesi.

İkisi farklı dizinlere dokunuyor ve hiçbir açık PR ile çakışmıyor. Her biri küçük ve yerelde test edilebilir.

**WS-3 (N-01/N-02) en az bu ikisi kadar acil.** Ancak #118/#119 ile aynı test dosyasını paylaştığı için “bağımsız” sayılmadı; o dalların sahibiyle hemen sıralama kararı verilmesi önerilir.
