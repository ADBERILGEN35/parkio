# Yönetici Özeti: Parkio Yeniden Denetimi

| | |
|---|---|
| **Denetlenen kaynak** | `origin/api` @ `2877ec81df17d76311125592dfcf66c4b8f6d324` |
| **Tarih** | 2026-09-28 |
| **Önceki denetim** | `aa865a25` (2026-09-24) |
| **Canlı ortam** | Gözlenmedi (yetki dışı) |

## Genel durum

Önceki denetimden bu yana dört gün geçti ve api dalına 53 commit eklendi. Ekip en ağır bulguların çoğunu **hızlı ve iyi test edilmiş düzeltmelerle** kapattı:

- **Waitlist admin export'u:** Kimlik doğrulaması olmadan erişilebilme sorunu (F-01) kapandı.
- **Eksik yedeğin “tamam” sayılması (F-02):** Düzeltildi. Yeni test gerçek betikleri çalıştırıyor, 40/40 geçti.
- **Sentetik harita anahtarı deploy guard'ı (F-05):** Artık gerçekten çalışıyor ve imajın içini doğruluyor. Test 116/116 geçti.
- **Zamanlanmış güvenlik ve entegrasyon koşuları:** Artık güncel kodda çalışıyor.

Bu değişiklikleri kendim yeniden çalıştırarak doğruladım.

**Ancak geri yükleme düzeltmesi yeni bir sorun yarattı.** Güvensiz geri yükleme engellendi, ama şu an **desteklenen bir üretim kurtarma yolu yok.** Runbook'taki komutlar reddediliyor. Tek çalışan yol `--isolated-fixture` adlı bir bayrak ve bu bayrak “izole” ortam yerine doğrudan **üretim veritabanlarına** yazmaya izin veriyor. Bunu yeniden ürettim.

Ayrıca uyarıların (Alertmanager) üretim setinde hâlâ kapalı olması, yedekler artık doğru biçimde başarısız olsa da bunu kimsenin fark etmeyebileceği anlamına geliyor.

**Puanlar**

| Değerlendirme | 09-24 | 09-28 |
|---|---|---|
| Genel | 5.5 | **5.7 / 10** |
| Kaynak kod kalitesi | ~6.0 | ~6.2 |
| Üretime hazırlık | ~4.0 | ~4.5 |
| Doğrulama kapsamı | orta-düşük | orta |

**Güncel bulgular:** 0 Kritik, **3 Yüksek**, 21 Orta, 15 Düşük; toplam 39.
- Önceki 40 maddeden 2'si tamamen, 2'si büyük ölçüde düzeldi. 5'i kısmen düzeldi. 30'u açık kaldı (çoğu kodu hiç değişmeyen alanlarda).
- Yeni bulgular: N-01, N-02, N-03.

## En güçlü alanlar

- **Düzeltme disiplini:** Her kritik düzeltme, gerçek betikleri stub'larla uçtan uca çalıştıran testlerle geldi. Önceki denetimdeki “yalnızca grep eden test” zayıflığı giderildi.
- **Yedek oluşturma:** COMPLETE işareti artık DB, ledger, MinIO ve bütünlük kontrolünün hepsine bağlı. Yazma atomik. Budama yalnızca başarılı koşudan sonra yapılıyor.
- **Kimlik doğrulama çekirdeği ve gateway sınırı:** Önceki denetimde güçlüydü, öyle kalıyor.
- **CodeQL'in “4 yüksek” uyarısı:** CodeQL'i yerelde aynı ayarlarla çalıştırdım. 6 yüksek sonucun hepsi yanlış pozitif veya başka bir kontrolle zaten karşılanmış: `img src` blob URL, test betiğinde `Array.includes` ve durumsuz API'de Origin kontrollü CSRF.

## En büyük riskler

1. **N-01, Yüksek. Üretim geri yükleme koruması tek bir bayrakla açılıyor.**
   - `--isolated-fixture` bileti kendisi üretiyor ve hedefin izole olduğunu hiç kontrol etmiyor.
   - Sonuç: Doğrulanmamış bir yedek üretimin üzerine yazılabilir ve silinmiş kullanıcıların verisi geri gelebilir.
2. **N-02, Yüksek. Desteklenen bir kurtarma yolu yok.**
   - DR runbook'undaki tek veritabanı, tam host kaybı ve yalnızca MinIO komutlarının hepsi reddediliyor.
   - Belgedeki 1–2 saatlik RTO geçersiz.
3. **F-04, Yüksek. Uyarılar kanonik üretim setinde kapalı.**
   - Yedek hataları artık doğru biçimde kırmızıya dönüyor, ama bu sinyal kimseye ulaşmayabilir.
4. **F-09, Orta. Slack relay tek bir bozuk yanıtla sonsuz döngüye giriyor ve aynı mesajı tekrar gönderiyor.** Yeni SHA'da yeniden üretildi; düzeltilmedi.
5. **Deploy tekrarlanabilirliği ve CI hijyeni.**
   - 6 servis hâlâ pinsiz ve host'ta build ediliyor (F-11).
   - CI rollback kırık (F-06).
   - Dependabot hâlâ bayat master'ı hedefliyor. Master'daki eski zamanlanmış koşular haftalardır her seferinde başarısız oluyor ve bu gürültü gerçek bir hatayı gizleyebilir (F-07R).
   - Yeni: MinIO imajları özel GHCR'de; host kaybında gereken registry kimlik bilgisi runbook'ta yok (N-03).

## Sürüm önerisi

**Sınırlı beta** (kapalı veya davetiyeli, küçük kohort): **Koşullu olarak desteklenebilir.** Önceki dört koşulun üçü karşılandı (F-01, F-02, F-05). Kalan koşullar:
- Alertmanager'ın canlıda uyarı ilettiğini kanıtlayın.
- `--isolated-fixture` üretim hedefini reddetsin ve yazılı, onaylı bir break-glass prosedürü olsun.
- Son yedeklerin COMPLETE ve `minioOk=1` olduğunu, host'un GHCR'den imaj çekebildiğini kontrol edin.

**Geniş sürüm:** **Desteklenemez.** Erasure kapsamını doğrulayabilen bir üretim geri yükleme kipi ve bununla yapılmış ölçülmüş bir tatbikat gerekiyor; ayrıca P1 ve gizlilik/güvenlik P2 maddeleri kapanmalı.

**Üretime hazırlık:** Canlı durum gözlenmediği için **kurulamaz.**

## Sonraki beş adım

1. **P0-A:** Alertmanager'ı doğrulayın ve kanonik üretim setine alın. Dead-man's switch ekleyin.
2. **P0-B:** `--isolated-fixture` hedefin izole olduğunu kanıtlasın. Kendi kendine bilet üretimi kaldırılsın. Bu iş #118/#119 ile aynı test dosyasına dokunduğu için o dalların sahibiyle sıralanmalı.
3. **P0-C:** DR runbook'unu güncelleyin. Onaylı break-glass prosedürünü yazın ve izole ortamda bir kez prova edin.
4. **P1-1:** Slack relay crash-loop'unu düzeltin ve regresyon testi ekleyin.
5. **P1-3 ve P1-8:** Master'daki bayat zamanlamaları kaldırın ve Dependabot'u api'ye yönlendirin. CodeQL uyarılarını gerekçesiyle kapatın.

## Önemli sınırlamalar

- Canlı ortam, branch protection kuralları ve GitHub code-scanning uyarı kimlikleri doğrulanamadı.
- Docker gerektiren testler çalıştırılamadı: web guard Part B/C, slack_biz e2e ve restore drill.
- Kodu değişmeyen alanlarda (ön yüz, mobil, çoğu servis) önceki kanıt taşındı; bu alanlar yeniden tarayıcıda test edilmedi.
- #118/#119 taslakları ayrıntılı güvenlik incelemesinden geçirilmedi.
- Bu denetim tüm kusurları bulduğunu iddia etmez. Kapsam dışı kalanlar `COVERAGE-MATRIX.md` §4'te listelidir.
