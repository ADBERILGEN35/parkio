# Mimari ve Tehdit Modeli: Parkio (`2877ec81`)

## Bu belge nasıl okunmalı

Sistem topolojisi, bileşen tablosu ve temel tehdit modeli `aa865a25`'ten bu yana **değişmedi**:
- 10 Spring servisi ve servis başına ayrı Postgres/PostGIS
- Gateway içinde waitlist
- Kafka (PLAINTEXT), Redis, MinIO + ClamAV
- Host üzerinde systemd ile çalışan slack_biz relay ve New Relic pilotu
- Tek VPS üzerinde Caddy kenarı

Tam harita ve bileşen tablosu `../parkio-comprehensive-audit-20260924/ARCHITECTURE-AND-THREAT-MODEL.md` dosyasındadır; o dosya bu revizyon için de doğrulandı. Değişmeyen dizinler için `git diff aa865a25 2877ec81` boş (`services/*/src/main` altında yalnızca gateway ve parking değişti).

Bu belge yalnızca **mimari açıdan anlamlı değişiklikleri** ve bunların tehdit modeline etkisini kaydeder.

## 1. Değişen güven sınırları ve kontroller

| Alan | `aa865a25` | `2877ec81` | Tehdit modeline etkisi |
|---|---|---|---|
| Gateway'in yerel controller'ları (waitlist admin/export) | Yöntem bazlı koruma, epoch/durum kontrolü yok | Yol bazlı koruma: JWT + rol + epoch + hesap durumu. Paylaşılan `SessionEpochVerifier` ve `AccountStatusVerifier` (`gw/infrastructure/security/`) | Kimliksiz erişim kapandı (F-01). Gateway'e kodlanmış iş mantığı hâlâ ayrı bir güvenlik zinciri gerektiriyor. Mimari risk azaldı ama kaynağı duruyor. |
| Yedek oluşturma | COMPLETE yalnızca DB + ledger'a bağlıydı | COMPLETE: DB + ledger + MinIO (sealed) + SHA256SUMS. Atomik `mv`, kesinti trap'i, başarıya bağlı budama | Offsite damga bütünlüğü güvenilir hale geldi (F-02). |
| Geri yükleme | Kontrolsüz üretim restore | Decrypt öncesi preflight, cutoff ve ledger birleştirmesi. **Üretim restore'u koşulsuz reddediliyor.** “İzole kip” bir CLI bayrağıyla açılıyor ve bileti betiğin kendisi üretiyor | Yeni bir güven sınırı ortaya çıktı: **“izole” ile “üretim” ayrımı** yalnızca operatörün beyanına dayanıyor (N-01). Kurtarma yetenekleri kayboldu (N-02). |
| Web imaj sürümü | Env satırına dayalı kara liste | Compose modeli render ediliyor, seçilen imajın bundle'ı inceleniyor, digest bağlanıyor (`pull_policy: never`), break-glass token'ı var | Sentetik harita anahtarlı imajın üretime çıkma riski büyük ölçüde azaldı (F-05). Kalıntı: kara liste (F-05R). |
| Tedarik zinciri: MinIO/mc | quay.io digest | Özel GHCR (`ghcr.io/adberilgen35/parkio/{minio,mc}`), yalnızca amd64 doğrulanmış | Harici registry bağımlılığı azaldı, ama **özel registry kimlik bilgisi artık yedekleme ve DR yolunun parçası** (N-03). |
| Zamanlanmış CI | master'da bayat kopyalar | master'daki dağıtıcı izin listesindeki 10 workflow'u `--ref api` ile başlatıyor. Bayat kopyalar da hâlâ çalışıyor | Güncel kod taranıyor. Aynı anda sürekli kırmızı bayat koşular gürültü üretiyor (F-07R). |
| Belediye doluluğu (İSPARK) | Kapalı otoparkta da doluluk yayımlanıyordu | Sorgu anında `isOpen` politikası uygulanıyor. Açık değilse doluluk UNAVAILABLE | İşlevsel doğruluk arttı (F-22'nin bir kısmı). |

## 2. Güncellenmiş kötüye kullanım senaryoları

| Senaryo | Sınır | `aa865a25` | `2877ec81` |
|---|---|---|---|
| Anonim kullanıcı yöntem hilesiyle admin export'unu tetikler | İnternet → gateway | Mümkündü | **Kapalı** (6 yöntem 401) |
| İptal edilmiş admin token'ıyla waitlist export'u | Gateway | Mümkündü (15 dk) | **Kapalı** (epoch + durum kontrolü). Auth admin API'lerinde rol iptali hâlâ 15 dk gecikmeli (F-13) |
| Eksik yedeğin COMPLETE sayılması | Host → offsite | Doğrulanmıştı | **Kapalı** |
| Operatörün erasure kapsamı doğrulanmamış yedeği üretime yüklemesi | Operatör → üretim DB | Varsayılan yol buydu | Varsayılan yol kapandı. **Tek bir bayrakla hâlâ mümkün** (N-01) |
| Host kaybında belgelenmiş kurtarmanın çalışmaması | DR | Güvensizdi ama çalışıyordu | **Belgelenmiş yol reddediliyor** (N-02) |
| Sentetik anahtarlı web imajının üretime çıkması | CI → host | Guard etkisizdi | Guard etkili. `REPLACE_ME` değerleri kaçıyor (F-05R) |
| Bozuk Slack yanıtıyla sonsuz yeniden gönderim | Relay | Doğrulanmıştı | **Değişmedi**, yeniden üretildi (F-09) |
| Uyarıların kimseye ulaşmaması | İzleme | Config'te doğrulanmıştı | **Değişmedi** (F-04). F-02 sonrası yedek hataları “sessiz COMPLETE yokluğu” olarak görünüyor; uyarı zinciri artık daha kritik |
| İç servis ele geçirilince her kullanıcıyı taklit etme | İç ağ | Mimari olarak mümkün | **Değişmedi** (F-16) |

## 3. Yönetim ve kurtarma yolları (güncel)

| Yol | Durum |
|---|---|
| `scripts/restore-hosted-beta.sh` | Üretimde reddediliyor (exit 3). `--isolated-fixture` ile açılıyor (N-01). |
| `scripts/restore-database.sh` | Tekil üretim restore'u reddediliyor. Bayrakla aynı durum. |
| `--only minio` | Reddediliyor. Bayrakla aynı durum. |
| Break-glass (web guard) | `PARKIO_SKIP_WEB_MAP_GUARD=I_ACCEPT_UNVERIFIED_WEB_IMAGE`: açık ve adlandırılmış. Restore için buna benzer bir kip **yok**. |
| CI rollback | Değişmedi, kırık (F-06). |
| Operasyonel durum kurtarması (#101–#104) | HOLD. |
| Erasure kalıcı kayıt ve sınır kurtarması (#118/#119) | Taslak. Varsayılan kapalı. Üretim sertifikasyonu yok. |

## 4. Mimari değerlendirme (güncel not)

**Ekibin dört günlük tepkisi.** Ekip kritik bulgulara hızlı ve testli düzeltmelerle yanıt verdi:
- `test-backup-complete-gate.sh` 40, `test-restore-safe-preflight.sh` 25 ve guard testi 116 kontrol içeriyor.
- Bu testler gerçek betikleri stub'larla çalıştırıyor. Bu, önceki denetimdeki “yalnızca grep eden test” zayıflığının doğrudan düzeltilmesi.

**Olumsuz taraf.** Kurtarma alt sistemi bir “güvenlik kilidi + kaçış bayrağı” deseniyle çözüldü. İki sonucu var:
- **Operabilite:** Kilit hangi koşulda açılacağını tanımlamıyor, dolayısıyla desteklenen bir üretim yolu yok.
- **Güvenlik:** Kaçış bayrağı gerçek bir izolasyon kontrolü yapmıyor.

**Mimari kök neden aynı:** Erasure kanıtı dağıtık. 10 servisin her birinde ayrı işleyici ve tombstone tablosu var (F-10). Bu yüzden “yedeğin erasure kapsamını doğrulamak” zor bir dağıtık sistem problemi haline geliyor. #118/#119 bu problemi çözmeye çalışıyor; henüz taslak durumunda.
