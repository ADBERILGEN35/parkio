# Yönetici Özeti — Parkio Kapsamlı Denetim

**Denetlenen kaynak:** `origin/api` @ `aa865a255564464bed207a9061244af2641edd3d` · **Tarih:** 2026-09-24 · **Canlı ortam:** gözlenmedi (yetki dışı)

## Genel durum

Parkio'nun **kod çekirdeği sağlam**. Asıl sorunlar yeni eklenen yan yollarda ve **işletme tarafında**: yedeklerin doğruluğu, uyarıların gerçekten birine ulaşması, üretime hangi imajın gittiği ve geri alma yolları. Bu alanlar kod kalitesinin gerisinde kalıyor.

Genel puan **5.5 / 10**.
- Kaynak kod kalitesi: **~6.0**
- Üretime hazırlık: **~4.0**
- Doğrulama kapsamı: **orta-düşük**

Toplam 39 bulgu var: **0 Kritik, 4 Yüksek, 23 Orta, 12 Düşük**. Buna içeriği okunamayan 1 doğrulama boşluğu eklenir: CodeQL'in 4 “high” uyarısı.

## En güçlü alanlar

- **Kimlik doğrulama:**
  - JWT yalnızca RS256 ile imzalanıyor; `kid`, issuer ve audience zorunlu.
  - Refresh token her kullanımda yenileniyor; eski token yeniden kullanılırsa tüm aile iptal ediliyor.
  - Token'lar veritabanında hash'li saklanıyor; davetiyeler atomik olarak tüketiliyor.
  - Kayıt modu varsayılan olarak **kapalı**.
- **Gateway sınırı:** İstemcinin gönderdiği `X-User-*` başlıkları siliniyor. Yol hileleri (`..`, `//`) reddediliyor. Üretimde yalnızca Caddy dışarıya açık.
- **Veri erişimi:**
  - Tüm SQL/PostGIS sorguları parametreli; birimler ve SRID doğru.
  - Nesne sahipliği (IDOR) kontrolleri tutarlı.
  - Outbox/inbox ve DLT deseni yerinde.
  - Medya yükleme iyi sertleştirilmiş: MIME/magic byte kontrolü, ClamAV fail-closed, EXIF temizleme.
- **Web uygulaması:**
  - Access token yalnızca bellekte, refresh token HttpOnly cookie'de.
  - TR/EN çeviri anahtarları birebir eşit (1610 anahtar).
  - Kimlik doğrulama sayfalarında axe **0 ihlal** buldu.
- **Belgeler:** Operasyon belgeleri kendi eksiklerini açıkça yazıyor. Önceki denetimin bazı yüksek bulguları düzeltilmiş: yol kanonikleştirme, atomik inbox, AI doğrulamanın fail-open olması.

## En büyük riskler

1. **Yedekler eksikken “tamam” sayılabiliyor (F-02, Yüksek).**
   - MinIO medya aynası başarısız olsa da yedek `COMPLETE` olarak işaretlenip offsite'a yükleniyor. Bu, yerelde sentetik ortamla yeniden üretildi.
   - DR geri yükleme yolu damganın başarılı olup olmadığını, bütünlüğünü ve sonradan yapılan hesap silmelerini kontrol etmiyor (F-03). Bu yüzden silinmiş kullanıcıların verisi geri gelebilir.
2. **Uyarılar büyük olasılıkla kimseye ulaşmıyor (F-04, Yüksek).**
   - Kanonik üretim compose setinde Alertmanager kapalı; yalnızca elle başlatılıyor.
   - Yedek hatası uyarıları dahil hiçbir uyarının teslim edildiği kanıtlanmadı.
3. **Sürüm güvenliği zayıf (F-05, F-11, F-12).**
   - Sentetik harita anahtarını engellemesi gereken guard betiği çalıştırılamaz modda (`100644`) ve kolayca atlatılıyor.
   - 6 servis üretimde pinsiz; host'taki checkout'tan build ediliyor.
   - Deploy betikleri farklı compose setleri kullanıyor.
   - CI'daki rollback yolu çalışmıyor (F-06).
4. **Waitlist admin export'u kimlik doğrulamasız HEAD isteğiyle tetiklenebiliyor (F-01).**
   - Test ile doğrulandı: GET 401 dönüyor, HEAD 200 `text/csv` dönüyor.
   - Kişisel veri satırları sızmıyor. Ancak dosya boyutu sızıyor ve kimliksiz, sınırsız bir veritabanı sorgusu tetiklenebiliyor.
   - Düzeltmesi tek satır.
5. **Olay ve gizlilik akışlarında sessiz hatalar (F-09, F-10, F-17, F-25).**
   - Slack relay tek bir bozuk yanıtla sonsuz yeniden başlatma döngüsüne giriyor ve aynı mesajı sınırsız tekrar gönderiyor. Yeniden üretildi.
   - Hesap silme onayı (erasure ack) veritabanı işlemi tamamlanmadan gönderiliyor.
   - Kullanıcı konum logları süresiz saklanıyor.
   - New Relic bütçe defteri kaybolursa harcama sayacı sıfırlanıyor. Düzeltmesi #101–#104'te, henüz birleştirilmedi.

## Sürüm önerisi

- **Sınırlı beta:** Kapalı veya davetiyeli, küçük bir kohortla **koşullu olarak desteklenebilir**. Önce şunlar yapılmalı:
  - F-01 düzeltilmeli ve gateway yeniden pinlenmeli.
  - F-02 düzeltilmeli.
  - Son yedeklerin `minioOk` değeri elle kontrol edilmeli.
  - Alertmanager'ın canlıda çalıştığı kanıtlanmalı.
  - Web pininin doğruluğu elle teyit edilmeli.
- **Geniş sürüm:** Şu an **desteklenmiyor**. P1 maddeleri ve gizlilik/güvenlik P2 maddeleri kapanmalı, ayrıca gerçek bir yedekle ölçülmüş bir geri yükleme tatbikatı yapılmalı.
- **Üretime hazırlık:** Bu denetimle **kurulamaz**. Çalışan imajlar, host yapılandırması, gerçek yedeklerin başarısı ve bildirim teslimi gözlenmedi.

## Sonraki beş adım

1. **Waitlist admin filtresi (P0-1):** Filtreyi yöntemden bağımsız hale getirin ve oturum/durum kontrolünü ekleyin. Gateway'i yeniden yayınlayıp pinleyin.
2. **Yedek kapısı (P0-2 ve P0-4):** COMPLETE kapısına MinIO sonucunu ekleyin ve uçtan uca bir test yazın. Son yedeklerin manifestlerini elle kontrol edin. Bu iş `backup-hosted-beta.sh`'ye dokunduğu için PR #104 ile sıralanmalı.
3. **Uyarı teslimi (P0-3):** Canlıda uyarıların ulaştığını doğrulayın. Alertmanager'ı kanonik üretim setine alın; bir dead-man's switch ekleyin.
4. **Deploy güvenliği (P1-2, P1-3, P1-9):** Deploy guard'ını düzeltip CI'a bağlayın, CI rollback'i onarın ve tüm deploy yollarında tek bir compose seti kullanın.
5. **CI hedefleri (P1-4, P1-5):** Zamanlanmış güvenlik taramalarını ve Dependabot'u `api` dalına yönlendirin. CodeQL'in 4 yüksek uyarısını sınıflandırın.

## Önemli sınırlamalar

- Canlı ortam, branch protection kuralları, CodeQL uyarı içerikleri, gerçek sağlayıcılar (Slack, New Relic, Resend, MapTiler), kimlik gerektiren web rotaları ve mobil çalışma zamanı **doğrulanmadı**.
- Bu denetim olası tüm kusurları bulduğunu iddia etmez. Kapsam dışı kalanlar `COVERAGE-MATRIX.md` §5'te listelidir.
