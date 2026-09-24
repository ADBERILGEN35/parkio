# SCORECARD — Parkio (`aa865a25`, 2026-09-24)

## 1. Puanlama çıpaları (puanlamadan önce tanımlandı)

| Puan | Anlam |
|---|---|
| 0–2 | Eksik veya kritik derecede güvensiz |
| 3–4 | Çözülmemiş büyük zayıflıklar |
| 5–6 | Önemli boşluklarla birlikte çalışabilir |
| 7–8 | Sınırlı boşluklarla güçlü uygulama |
| 9–10 | Güçlü uygulama ve kapsamlı doğrulama |

Kurallar: İncelenmemiş alan sağlıklı sayılmadı. Bir kök neden yalnızca bir boyutu düşürdü (birincil boyut parantez içinde belirtildi). Canlı ortam gözlenmediği için hiçbir boyut 9–10 alamaz.

## 2. Boyut puanları

| # | Boyut | Ağırlık | Puan | Kanıt kapsamı | Güven | Puanı düşüren bulgular | İyileştirmek için |
|---|---|---|---|---|---|---|---|
| 1 | Güvenlik ve gizlilik | %20 | **6.0** | Yüksek (auth/gateway/user tam statik; F-01 dinamik) | Orta | F-01, F-13, F-14, F-15, F-16, F-17, F-18, F-28, F-35–F-37, F-39 | F-01 + F-13 düzelt; enumeration/kilit; iç kimlik (auth-service JWT'den yetki); konum log TTL; rıza sürümü |
| 2 | İşlevsel doğruluk ve domain bütünlüğü | %15 | **6.5** | Orta-Yüksek | Orta | F-19, F-20, F-21, F-22, F-34, F-38 | Waitlist hata eşlemesi, harita hata durumu, URL dili, doluluk semantiği |
| 3 | Mimari ve bakım kolaylığı | %10 | **5.5** | Orta | Orta | Waitlist'in gateway'de olması (F-01/F-13 kök nedeni), 10 servis/10 DB tek VPS, kopya altyapı kodu (F-32), 22+ compose, iki dal modeli | Waitlist'i kenardan ayırmak veya gateway'de tek güvenlik zinciri; ortak platform kütüphanesi (outbox/erasure/filtreler); tek deploy seti |
| 4 | Veri ve olay tutarlılığı | %10 | **6.0** | Yüksek (outbox/inbox/relay) | Orta-Yüksek | F-09, F-10, F-24, F-32, F-33 | Relay crash-loop, erasure ack commit sonrası, redrive filtresi |
| 5 | Web/mobil UX ve erişilebilirlik | %10 | **6.5** | Orta (auth + explore + pazarlama; kimlikli rotalar yok) | Orta | F-20, F-30 (+ F-19/F-21 birincil boyut 2'de) | Harita hata banner'ı, retry, kontrast; kimlikli rotalarda axe |
| 6 | Performans ve ölçeklenebilirlik | %5 | **6.0** | Düşük (statik; yalnızca CI k6 smoke) | Düşük | F-23, F-34, F-38 (N+1) | Besleme boyut sınırı, akışlı export, batch sorgular; gerçek yük testi |
| 7 | Güvenilirlik ve gözlemlenebilirlik | %10 | **4.5** | Orta | Orta | F-04, F-06, F-26 (uyarı teslimi kanıtsız) | Alertmanager'ı üretim setine al + dead-man's switch; rollback'i düzelt |
| 8 | Yedekleme ve felaket kurtarma | %10 | **3.5** | Orta-Yüksek (betikler dinamik test edildi) | Orta-Yüksek | F-02, F-03, F-25, F-27 | COMPLETE kapısı (MinIO), güvenli restore yolu, gerçek damga drill'i, operasyonel durum yedeği |
| 9 | Test kalitesi ve CI/CD güvenliği | %5 | **5.0** | Yüksek | Orta-Yüksek | F-07, F-08, F-26, F-40 | Zamanlanmış işleri api'ye, Dependabot hedefi, yanlış-yeşil düzeltmeleri, CodeQL triage |
| 10 | Deploy tekrarlanabilirliği ve operasyonel hazırlık | %5 | **4.0** | Orta (host durumu bilinmiyor) | Orta | F-05, F-11, F-12, F-29 | Tüm servisler pinli imaj, imza doğrulama, tek compose seti, çalışan guard |

## 3. Ağırlıklı hesap

```
6.0×0.20 = 1.20
6.5×0.15 = 0.98
5.5×0.10 = 0.55
6.0×0.10 = 0.60
6.5×0.10 = 0.65
6.0×0.05 = 0.30
4.5×0.10 = 0.45
3.5×0.10 = 0.35
5.0×0.05 = 0.25
4.0×0.05 = 0.20
Toplam  = 5.53 → **5.5 / 10**
```
Ağırlıklar yeniden normalleştirilmedi (tüm boyutlara en az kısmi kanıtla puan verildi). Performans boyutu düşük güvenlidir; bu boyut hariç tutulsa (ağırlık %95'e normalleştirilerek) sonuç yine ≈ 5.5'tir.

## 4. Ayrı değerlendirmeler

| | Değerlendirme | Puan | Gerekçe |
|---|---|---|---|
| **A. Kaynak mühendislik kalitesi** | Kod düzeyi (boyut 1–6 + 9, operasyon hariç) | **~6.0 / 10** | Kimlik doğrulama çekirdeği (RS256 pinleme, refresh rotasyonu + yeniden kullanım tespiti, hash'li token'lar, atomik davetiye tüketimi), parametreli PostGIS sorguları, outbox/inbox + DLT, medya yükleme sertleştirmesi, token'ın bellekte tutulması, i18n anahtar eşitliği güçlü. Zayıflıklar yeni eklenen yan yollarda (gateway içi waitlist, relay, erasure koordinasyonu) yoğunlaşıyor. |
| **B. Üretime hazırlık** | Güvenle konuşlandırma, izleme, kurtarma | **~4.0 / 10** | Yedekler eksikken COMPLETE sayılabiliyor, DR restore yolu güvensiz, uyarı teslimi üretim setinde yok, 6 servis pinsiz, deploy guard etkisiz, CI rollback kırık. |
| **C. Doğrulama kapsamı** | Bu denetimin kanıt derinliği | **Orta-düşük** | Statik kapsam geniş (tüm servisler, CI, ops); dinamik kanıt hedefli (6 yeniden üretim, 9 mevcut test paketi, tarayıcı/axe). Canlı ortam, gerçek sağlayıcılar, branch protection, CodeQL uyarıları, kimlikli UI rotaları ve mobil çalışma zamanı **doğrulanmadı**. |

Ortalama, kritik engelleri gizlememelidir: F-02/F-03 (yedek bütünlüğü) ve F-04 (uyarı teslimi) tek başlarına sürüm kapısıdır.

## 5. Sürüm kapıları ve karar

| Karar | Sonuç | Koşullar |
|---|---|---|
| **Sınırlı beta** (kapalı/davetiyeli, küçük kohort, mevcut waitlist) | **Koşullu olarak desteklenebilir** | Önce: (1) F-01 düzeltilip pinli gateway yeniden yayınlanmalı; (2) F-02 düzeltilmeli veya son yedeklerin `minioOk=1` olduğu elle doğrulanmalı; (3) F-04 — Alertmanager'ın canlıda çalıştığı ve sentetik bir uyarının Slack'e ulaştığı kanıtlanmalı; (4) F-05 — guard'ın çalıştığı veya pinli web digest'inin elle doğrulandığı kayıt altına alınmalı; (5) F-03 düzeltilene kadar DR runbook'una “yalnızca `success=1` + doğrulanmış damga” el kontrolü eklenmeli. Kayıt modu `closed` veya `invite` kalmalı. |
| **Geniş sürüm** (açık kayıt, genel kullanıcı) | **Desteklenemez** | Ek olarak tüm P1 maddeleri (F-03, F-06, F-07, F-08 triage, F-09, F-10, F-11, F-12, F-27) ve P2 güvenlik/gizlilik maddeleri (F-13–F-18) kapatılmalı; gerçek damga ile ölçülmüş RTO'lu restore drill'i; F-25 (#104 veya eşdeğeri) birleştirilmeli. |
| **Üretime hazırlık** | **Bu denetimle kurulamaz** | Canlı durum (D) tamamen bilinmiyor: çalışan imajlar, host `.env`, Alertmanager, yedeklerin gerçek başarısı, Slack/NR teslimi gözlenmedi. Karar için dağıtım gözlemi (salt okunur host denetimi) gerekir. |
