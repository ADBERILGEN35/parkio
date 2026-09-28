# SCORECARD: Parkio (`2877ec81`, 2026-09-28)

## 1. Puanlama çıpaları (puanlamadan önce tanımlandı; önceki denetimle aynı)

| Puan | Anlam |
|---|---|
| 0–2 | Eksik veya kritik derecede güvensiz |
| 3–4 | Çözülmemiş büyük zayıflıklar |
| 5–6 | Önemli boşluklarla birlikte çalışabilir |
| 7–8 | Sınırlı boşluklarla güçlü uygulama |
| 9–10 | Güçlü uygulama ve kapsamlı doğrulama |

**Kurallar:**
- Kök neden tek bir boyutta sayıldı.
- Canlı ortam gözlenmedi; bu yüzden hiçbir boyut 9–10 alamaz.
- Kodu değişmemiş boyutların puanı ancak yeni kanıt varsa değişti.

## 2. Boyut puanları

| # | Boyut | Ağırlık | 09-24 | **09-28** | Kanıt kapsamı | Güven | Puanı düşüren bulgular | İyileştirmek için |
|---|---|---|---|---|---|---|---|---|
| 1 | Güvenlik ve gizlilik | %20 | 6.0 | **6.3** | Yüksek (yerel CodeQL eklendi) | Orta | F-13 (auth), F-14–F-18, F-28, F-35–F-37, F-39 | Auth epoch artışı, enumeration/kilit, iç kimlik, konum log TTL, rıza sürümü |
| 2 | İşlevsel doğruluk | %15 | 6.5 | **6.5** | Orta-Yüksek | Orta | F-19–F-22 (İZUM kısmı), F-34, F-38 | Waitlist hata eşleme, harita hata durumu, URL dili, İZUM semantiği |
| 3 | Mimari ve bakım kolaylığı | %10 | 5.5 | **5.5** | Orta | Orta | Gateway içindeki waitlist, 10 servis/10 DB, kopyalanmış altyapı kodu, dağıtık erasure kanıtı | Ortak platform kütüphanesi, erasure koordinasyonunun sadeleştirilmesi |
| 4 | Veri ve olay tutarlılığı | %10 | 6.0 | **6.0** | Yüksek | Orta-Yüksek | F-09 (yeniden üretildi), F-10, F-24, F-32, F-33 | Relay crash-loop, erasure ack commit sonrasına, redrive filtresi |
| 5 | UX ve erişilebilirlik | %10 | 6.5 | **6.5** | Orta (kod değişmedi) | Orta | F-20, F-30 | Harita banner'ı, retry, kontrast |
| 6 | Performans ve ölçeklenebilirlik | %5 | 6.0 | **6.0** | Düşük | Düşük | F-23, F-34, F-38 | Boyut sınırları, akışlı export, yük testi |
| 7 | Güvenilirlik ve gözlemlenebilirlik | %10 | 4.5 | **4.5** | Orta | Orta | F-04 (daha kritik hale geldi), F-06, F-07R (sürekli kırmızı schedule'lar) | Alertmanager'ı üretim setine al, dead-man's switch, rollback |
| 8 | Yedekleme ve DR | %10 | 3.5 | **4.0** | Yüksek (40 + 25 test + harness) | Orta-Yüksek | **N-01, N-02**, F-25, F-27, N-03 | Güvenli break-glass/üretim restore kipi, izolasyon doğrulaması, ölçülmüş drill |
| 9 | Test kalitesi ve CI/CD | %5 | 5.0 | **6.0** | Yüksek | Orta-Yüksek | F-07R, F-26R, F-40, F-08 (GitHub'da triage edilmemiş) | Bayat schedule'ları kaldır, Dependabot hedefi, SHA pinleme |
| 10 | Deploy tekrarlanabilirliği ve operasyonel hazırlık | %5 | 4.0 | **4.5** | Orta | Orta | F-11 (6 pinsiz servis + media), F-12, F-29, F-05R | Tüm servisler için digest pin, tek compose seti |

**Puan değişikliklerinin gerekçeleri:**
- **Boyut 1 (+0.3):** F-01 ve F-13'ün waitlist kısmı kapandı; yerel CodeQL gerçek bir açık bulmadı.
- **Boyut 8 (+0.5):** Yedek oluşturma artık güvenilir ve iyi test edilmiş (F-02). Restore girdileri doğrulanıyor. Buna karşılık üretim kurtarması desteklenmiyor (N-02) ve koruma tek bir bayrakla aşılabiliyor (N-01). Yedekleme tarafı ~6, geri yükleme tarafı ~2; ortalama 4.0.
- **Boyut 9 (+1.0):** Önceki yanlış yeşiller düzeltildi; CI'da gerçek betikleri çalıştıran kapsamlı testler var.
- **Boyut 10 (+0.5):** Guard etkili hale geldi; parking pini belgelendi.

## 3. Ağırlıklı hesap

```
6.3×0.20 = 1.26
6.5×0.15 = 0.98
5.5×0.10 = 0.55
6.0×0.10 = 0.60
6.5×0.10 = 0.65
6.0×0.05 = 0.30
4.5×0.10 = 0.45
4.0×0.10 = 0.40
6.0×0.05 = 0.30
4.5×0.05 = 0.23
Toplam  = 5.71 → **5.7 / 10**   (2026-09-24: 5.5)
```

Yeniden normalleştirme yapılmadı. Düşük güvenli performans boyutu hariç tutulsa da sonuç ≈ 5.7.

## 4. Ayrı değerlendirmeler

| | Değerlendirme | Puan | Gerekçe |
|---|---|---|---|
| **A. Kaynak mühendislik kalitesi** | Kod düzeyi | **~6.2 / 10** (önce ~6.0) | Düzeltmeler küçük, odaklı ve güçlü testlerle geldi. Değişmeyen alanlardaki bulgular duruyor. |
| **B. Üretime hazırlık** | Konuşlandırma, izleme, kurtarma | **~4.5 / 10** (önce ~4.0) | Yedek bütünlüğü ve deploy guard'ı düzeldi. Uyarı teslimi (F-04), desteklenen kurtarma yolu (N-02), izolasyon koruması (N-01), 6 pinsiz servis ve CI rollback hâlâ açık. |
| **C. Doğrulama kapsamı** | Bu denetimin kanıt derinliği | **Orta** (önce orta-düşük) | Yerel CodeQL ile önceki boşluk büyük ölçüde kapandı. Değişen tüm kritik yollar dinamik test edildi. Canlı ortam, Docker gerektiren testler ve UI yeniden testi yok. |

**Önemli uyarı:** Ortalama puan engelleri gizlememeli. N-01, N-02 ve F-04 birlikte şu anlama geliyor: Yedekler artık doğru alınıyor, ama başarısız olduklarında kimse haberdar olmayabilir; bir felakette de kurtarma ya belgelendiği gibi çalışmaz ya da doğrulanmamış bir bayrakla yapılır.

## 5. Sürüm kapıları ve karar

| Karar | Sonuç | Koşullar |
|---|---|---|
| **Sınırlı beta** (kapalı veya davetiyeli, küçük kohort) | **Koşullu olarak desteklenebilir** (önceki kapıların üçü kapandı) | Açık kalan koşullar: **(1)** Alertmanager'ın canlıda çalıştığı ve sentetik bir uyarının Slack'e ulaştığı kanıtlanmalı (F-04). **(2)** Onaylı ve yazılı bir restore break-glass prosedürü olmalı, ve `--isolated-fixture` üretim hedefini reddetmeli (N-01, N-02). **(3)** Son gece yedeğinin COMPLETE ve `minioOk=1` olduğu, ve host'un GHCR'den `mc` imajını çekebildiği kontrol edilmeli (N-03). Kayıt modu `closed` veya `invite` kalmalı. |
| **Geniş sürüm** | **Desteklenemez** | Ek olarak şunlar gerekli: tüm P1 maddeleri (F-06, F-07R, F-09, F-10, F-11, F-12, F-27); P2 güvenlik ve gizlilik maddeleri (F-13–F-18); erasure kapsamı doğrulanabilir bir üretim restore kipi (#118/#119 veya eşdeğeri) ve bununla yapılmış ölçülmüş bir drill; F-25. |
| **Üretime hazırlık** | **Bu denetimle kurulamaz** | Canlı durum gözlenmedi: çalışan imajlar, Alertmanager, gerçek yedek sonuçları, GHCR oturumu ve bildirim teslimi. |
