# Parkio Beta — Davet Mesajı (Tester Invite)

Aşağıdaki Türkçe mesajı testçilere doğrudan iletebilirsiniz (Slack / WhatsApp / e-posta).
Kurulum ve hata bildirimi için bağlantıları kendi paylaşım ortamınıza göre düzenleyin.

---

Merhaba! 👋

**Parkio** kapalı betasına katılmanı istiyoruz. Parkio, sürücülerin boş sokak/otopark
yerlerini fotoğrafıyla paylaşıp birbirlerinin yerlerini doğruladığı, puan kazandığı bir
park-paylaşım uygulaması. Kısaca: bir yer bul, fotoğrafını yükle, paylaş; başkaları
doğrulasın ve kapsın.

**Önemli:** Bu bir **yerel (local) beta** — canlı/production değil. Her şey kendi
bilgisayarında Docker ile çalışıyor. Veriler test amaçlı, gerçek değil.

### Ne yapmanı bekliyoruz (~20–30 dakika)

1. Yeni bir hesap **oluştur** (register).
2. Kayıttan sonra **1–5 saniye bekle** (profil arka planda hazırlanıyor), sonra **giriş yap**.
3. **Profil** ve **Harita** ekranlarının açıldığını gör.
4. Bir **fotoğraf yükle** ve **yeni bir park yeri oluştur**.
5. Yerin **detayına** gir, **fotoğrafın göründüğünü** kontrol et.
6. **İkinci bir hesap** açıp ilk yerin **doğrula (verify)** ve **kap (claim)** akışını dene.
7. **Puanlarının** (gamification) güncellendiğine bak.

### Kurulum

Adım adım kurulum ve başlatma komutları runbook'ta:
`docker/BETA_RUNBOOK.md`. Gereksinimler: Docker Desktop ve Node.js 20+. İlk Docker
kurulumu ~5–6 dakika sürebilir (10 servis derleniyor), sonraki açılışlar hızlı.

### Bilinen küçük durumlar (hata değil)

- Kayıttan hemen sonra 1–5 sn "hesap aktif değil" görebilirsin — beklersen geçer.
- E-postalar küçük harfe çevrilir.
- Fotoğraf bağlantısı `host.docker.internal` kullanır; Docker Desktop'ta tarayıcıda
  sorunsuz açılır (bazı Linux/kısıtlı ağlarda açılmayabilir).
- Harita karoları internet ister; ilk açılışta boş harita görürsen interneti kontrol et.
- Yeni veritabanında harita boştur — önce bir yer oluşturman gerekir.

### Hata bildirimi

`docs/beta/TESTER_FEEDBACK_TEMPLATE.md` şablonunu doldur. Her hata için lütfen ekle:

- Ne yaptın / ne bekliyordun / ne oldu,
- **Önem derecesi** (Blocker / Major / Minor / Cosmetic),
- Ekrandaki hata mesajındaki **traceId**,
- **Ekran görüntüsü**,
- Gerekirse servis logu:
  `docker compose -f docker-compose.yml -f docker-compose.apps.yml logs <servis> --tail 80`.

Takıldığın her noktada yaz — kafa karıştıran küçük şeyler bile bizim için değerli.
Teşekkürler! 🚗💙
