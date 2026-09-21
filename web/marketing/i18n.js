/**
 * Parkio marketing i18n — Turkish default, English on explicit choice.
 * Persists to localStorage key parkio.marketing.locale.
 */
(function (global) {
  const STORAGE_KEY = 'parkio.marketing.locale';
  const DICT = {
    tr: {
      'meta.title': 'Parkio | Park yeri keşfi ve park zekâsı',
      'meta.description':
        'Parkio, topluluk paylaşımlı park bilgisi ile belediye park verisini haritada bir araya getirir. Hesap gerekmeden salt okunur Explore deneyimini açın.',
      'skip': 'İçeriğe atla',
      'nav.aria': 'Ana navigasyon',
      'nav.product': 'Ürün',
      'nav.how': 'Nasıl çalışır',
      'nav.trust': 'Güven',
      'nav.business': 'İş modeli',
      'nav.roadmap': 'Yol haritası',
      'nav.founder': 'Kurucu',
      'nav.waitlist': 'Bekleme listesi',
      'brand.tagline': 'Park zekâsı',
      'cta.explore': 'Park alanı keşfet',
      'cta.waitlist': 'Bekleme listesine katıl',
      'cta.signin': 'Giriş yap',
      'cta.context':
        'Bekleme listesi bir uygulama hesabı oluşturmaz. Hesap kaydı kapalıdır; mevcut kullanıcılar giriş yapabilir.',
      'lang.aria': 'Dil seçimi',
      'lang.tr': 'TR',
      'lang.en': 'EN',
      'hero.eyebrow': 'Canlı ürün · salt okunur Explore',
      'hero.h1.before': 'Park yeri keşfi,',
      'hero.h1.accent': 'daha net kaynak bağlamıyla.',
      'hero.lede':
        'Parkio, sürücülerin topluluk bilgisi ve mevcut belediye park verisiyle park yeri keşfetmesine yardımcı olur. Gerçek, salt okunur bir Explore deneyimi şu anda kullanılabilir — hesap gerekmez.',
      'proof.explore.label': 'Explore',
      'proof.explore.value': 'Anonim · salt okunur',
      'proof.data.label': 'Veri',
      'proof.data.value': 'Kaynak etiketli',
      'proof.reg.label': 'Kayıt',
      'proof.reg.value': 'Kapalı',
      'status.pill': 'Canlı ürün yüzeyi',
      'status.kicker': 'Ürün kanıtı',
      'status.title': 'Hesap olmadan Explore’u açın',
      'status.body':
        'Parkio’nun herkese açık Explore haritası, belediye atıflı gerçek tesisleri ve dürüst tazelik bilgisini gösterir. Uygun olduğunda müsaitlik gösterilir; bayat doluluk canlı envanter gibi sunulmaz.',
      'status.web': 'Web ürünü',
      'status.web.v': 'Çalışıyor',
      'status.muni': 'Belediye keşfi',
      'status.muni.v': 'Canlı (sınırlı)',
      'status.public': 'Herkese açık salt okunur Explore',
      'status.public.v': 'Canlı · hesap gerekmez',
      'status.account': 'Hesap kaydı',
      'status.account.v': 'Kapalı ve kontrollü',
      'status.note':
        'app.parkio.dev/explore canlı ürün rotasıdır — pazarlama illüstrasyonu veya kapalı kayıt hunisi değildir.',
      'product.kicker': 'Ürün',
      'product.h2': 'Bugün açabileceğiniz gerçek bir park ürünü.',
      'product.intro':
        'Parkio, topluluk ve belediye park sinyallerini bir araya getirirken kaynak, tazelik ve erişim sınırlarını net tutar. Anonim Explore canlıdır; hesap kaydı kapalıdır.',
      'product.f1.h': 'Park yeri keşfet',
      'product.f1.p': 'Odaklı bir harita deneyiminde tesisleri ve mevcut bağlamı inceleyin.',
      'product.f2.h': 'Belediye park verisi',
      'product.f2.p':
        'Desteklenen belediye tesis verisi, net atıfla sınırlı salt okunur bir sözleşme üzerinden yayınlanır.',
      'product.f3.h': 'Kaynak etiketli müsaitlik',
      'product.f3.p':
        'Okunabilir kaynak ve atıf tesis verisine eşlik eder. Müsaitlik, ürün bunu göstermeye uygun gördüğünde görünür.',
      'product.f4.h': 'Kontrollü erişim',
      'product.f4.p':
        'Salt okunur keşif, kimliği doğrulanmış katkı ve kişisel park özelliklerinden ayrıdır.',
      'how.kicker': 'Nasıl çalışır',
      'how.h2': 'Net bir park kararı akışı.',
      'how.intro':
        'Parkio, değişen gerçek dünya müsaitliğinin sabit kalacağını vaat etmeden belirsizliği azaltmak için tasarlanmıştır.',
      'how.s1.h': 'Keşfet',
      'how.s1.p': 'Explore’u açın ve desteklenen park bilgisini tek odaklı deneyimde inceleyin.',
      'how.s2.h': 'Anla',
      'how.s2.p':
        'Tesis, güncel olduğunda müsaitlik, tazelik ve kaynak bağlamını kullanarak gösterileni yorumlayın.',
      'how.s3.h': 'Karar ver',
      'how.s3.p': 'Yerel koşulların değişebileceğini dikkate alarak yolculuğunuza uyan seçeneği seçin.',
      'how.s4.h': 'Sorumlu kullan',
      'how.s4.p': 'Her zaman yol işaretlerine, yerel kurallara, ödeme gereksinimlerine ve yerindeki koşullara uyun.',
      'trust.kicker': 'Veri ve gizlilik',
      'trust.h2': 'Faydalı bilgi, net kaynakla.',
      'trust.intro':
        'Parkio, topluluk kaynaklı bilgiyi mevcut belediye veya kamuya açık park verisiyle birleştirir. Belediye veri setlerine sahip değildir ve evrensel gerçek zamanlı doluluk iddiasında bulunmaz.',
      'trust.notice':
        'Önemli: Park müsaitliği hızla değişebilir. Parkio karar desteği sunar; vardığınızda yerin hâlâ boş olacağını garanti etmez.',
      'trust.r1.h': 'Kaynak bilinci',
      'trust.r1.p': 'Yayımlanan belediye verisi okunabilir kaynak etiketi ve atıf içerir.',
      'trust.r2.h': 'Tazelik önemli',
      'trust.r2.p':
        'Ürünün yayın politikası artık güncel göstermeye uygun görmediğinde müsaitlik gizlenir veya bayat işaretlenir.',
      'trust.r3.h': 'Sınırlı yayın',
      'trust.r3.p': 'Herkese açık Explore salt okunur, coğrafi olarak sınırlı ve bilinçli olarak kısıtlıdır.',
      'trust.r4.h': 'Ortaklık ima edilmez',
      'trust.r4.p':
        'Kamuya açık belediye verisini göstermek tek başına ortaklık, onay veya resmi bağlılık anlamına gelmez.',
      'today.kicker': 'Parkio bugün',
      'today.h2': 'Canlı Explore ile çalışan bir ürün.',
      'today.intro':
        'Parkio’nun üretim altyapısı ve web ürünü çalışıyor. Anonim salt okunur Explore canlıdır. Hesap kaydı kapalıdır; kimliği doğrulanmış katkı ve kişisel akışlar girişin arkasındadır.',
      'today.l1': 'Üretim altyapısı canlıdır.',
      'today.l2': 'Anonim salt okunur Explore hesapsız kullanılabilir.',
      'today.l3': 'Belediye park keşfi atıfla yayımlanır.',
      'today.l4': 'Yayımlanan müsaitlik kaynak ve tazelik bağlamı taşır.',
      'today.l5': 'Hesap kaydı kapalı kalır.',
      'waitlist.kicker': 'Bekleme listesi',
      'waitlist.h2': 'Kayıtlar açıldığında haber verin.',
      'waitlist.intro':
        'Hesap oluşturmadan yalnızca e-posta adresinizi bırakın. Amacımız: kayıtlar açıldığında sizi bilgilendirmek. Başka pazarlama aboneliği yapılmaz.',
      'waitlist.email': 'E-posta adresi',
      'waitlist.consent':
        'Kayıtlar açıldığında Parkio’nun beni e-posta ile bilgilendirmesini kabul ediyorum. Onay için e-postamdaki bağlantıyı kullanacağım.',
      'waitlist.submit': 'Bekleme listesine katıl',
      'waitlist.submitting': 'Gönderiliyor…',
      'waitlist.success':
        'Teşekkürler. Adresiniz alındı. Listede kalmak için e-postanızdaki onay bağlantısını açıp Onayla’ya basın.',
      'waitlist.error.invalid': 'Geçerli bir e-posta adresi girin.',
      'waitlist.error.consent': 'Devam etmek için bilgilendirme onayını işaretleyin.',
      'waitlist.error.consentTime':
        'Onay zamanı geçersiz veya cihaz saati çok sapmış. Saat ayarlarınızı kontrol edip tekrar deneyin.',
      'waitlist.error.network': 'Bağlantı hatası. Lütfen tekrar deneyin.',
      'waitlist.error.rate': 'Çok fazla deneme. Lütfen daha sonra tekrar deneyin.',
      'waitlist.error.delivery':
        'Kaydınız alındı ancak onay e-postası gönderilemedi. Lütfen kısa süre sonra tekrar deneyin.',
      'waitlist.error.generic': 'Şu anda kaydedilemedi. Lütfen tekrar deneyin.',
      'waitlist.privacy': 'Gizlilik politikası',
      'waitlist.withdraw': 'Çekilme veya silme talebi için onay e-postasındaki bağlantıyı veya info@parkio.dev adresini kullanın.',
      'waitlist.mockNote': 'Yerel yalıtılmış mod: kalıcı sağlayıcı çalıştırılmıyor.',
      'waitlist.unavailable':
        'Kayıt bildirim listesi henüz açık değil. Onay veya çıkış bağlantınız varsa ilgili sayfayı kullanabilirsiniz.',
      'business.kicker': 'İş modeli',
      'business.h2': 'Parkio bir iş olarak nasıl ilerliyor',
      'business.intro':
        'İş modeli ürünle birlikte doğrulanıyor. Aşağıdaki yönler hipotezdir; şu an satışta olan teklifler değildir.',
      'business.c1.l': 'Bugün',
      'business.c1.h': 'Kurucu liderliğinde ve öz sermayeli',
      'business.c1.p':
        'Parkio, kapalı kayıtla canlı bir ürün işletir. Gelir, ücretli dönüşüm veya ticari ortaklık belgelenmemiştir.',
      'business.c2.l': 'Çekirdek ürün',
      'business.c2.h': 'Erişilebilir park keşfi',
      'business.c2.p':
        'Parkio günlük sürücüler için park keşfi ve park zekâsı geliştirir. Anonim Explore, gerçek kullanım doğrulanırken keşfi erişilebilir tutar.',
      'business.c3.l': 'Gelecek yön',
      'business.c3.h': 'İsteğe bağlı kolaylık ve zekâ',
      'business.c3.p':
        'Parkio, operatörler, belediyeler ve mobilite işletmeleri için isteğe bağlı premium kolaylık özellikleri ile toplulaştırılmış park zekâsı hizmetlerini değerlendiriyor.',
      'business.c4.l': 'Gizlilik ilkesi',
      'business.c4.h': 'Ürün değeri, gözetim değil',
      'business.c4.p':
        'Parkio bireysel konum geçmişi, ham hareket verisi veya kullanıcı kimliklerinden para kazanmayı planlamaz.',
      'business.note': 'Bu ticari yönler değerlendirme altındadır ve şu an satışta olan ürünler değildir.',
      'roadmap.kicker': 'Yol haritası',
      'roadmap.h2': 'Önce doğrula, sonra dikkatle genişlet.',
      'roadmap.intro':
        'Parkio’nun yol haritası bilinçli olarak tarihsizdir; ürün, operasyon ve veri hakları kanıtına bağlıdır.',
      'roadmap.now': 'Şimdi',
      'roadmap.now.h': 'Canlı Explore, dikkatli erişim',
      'roadmap.now.p':
        'Anonim salt okunur keşfi işlet, topluluk katkı kalitesini iyileştir ve gerçek kullanımı doğrularken kaydı kapalı tut.',
      'roadmap.next': 'Sonraki',
      'roadmap.next.h': 'Daha geniş ürün kapsamı',
      'roadmap.next.p':
        'Kanıt destekledikten sonra mobil deneyimi, kişisel park akışlarını ve daha geniş doğrulanmış park kapsamını genişlet.',
      'roadmap.later': 'Daha sonra',
      'roadmap.later.h': 'Dayanıklılık ve entegrasyonlar',
      'roadmap.later.p':
        'Operasyonel ölçeklenebilirliği güçlendir; teknik ve veri hakları incelemesinden sonra ek operatör ve belediye veri entegrasyonlarını değerlendir.',
      'about.kicker': 'Kurucu',
      'about.h2': 'Üründen operasyona kurucu liderliğinde.',
      'about.intro': 'Parkio, Türkiye merkezli bağımsız, öz sermayeli bir mobilite girişimidir.',
      'about.founder.l': 'Kurucu',
      'about.founder.p':
        'Parkio’nun kurucusu; ürün, teknik temel ve operasyonel yönü şekillendirmekten sorumludur.',
      'about.founder.link': 'LinkedIn’de Oğuzhan Taşyaran',
      'about.contact.kicker': 'İletişim',
      'about.contact.h': 'Parkio ile iletişim',
      'about.contact.p':
        'Ürün, veri kaynağı, iş, gizlilik veya teknik sorular için Parkio’nun herkese açık iş e-postasını kullanın.',
      'faq.kicker': 'SSS',
      'faq.h2': 'Mevcut aşama hakkında net yanıtlar.',
      'faq.q1': 'Parkio nedir?',
      'faq.a1':
        'Parkio, topluluk bilgisini mevcut belediye park verisiyle birleştiren, odaklı bir web deneyiminde sunulan park keşfi ve park zekâsı ürünüdür.',
      'faq.q2': 'Bugün ne canlı?',
      'faq.a2':
        'Parkio’nun üretim altyapısı ve web ürünü canlıdır. Anonim salt okunur Explore, app.parkio.dev/explore adresinde hesap oluşturmadan kullanılabilir. Kayıt kapalı ve kontrollüdür.',
      'faq.q3': 'Denemek için hesap gerekir mi?',
      'faq.a3':
        'Hayır. Explore hesapsız kullanılabilir. Giriş yalnızca kimliği doğrulanmış kişisel ve katkı özellikleri için gerekir. Yeni herkese açık kayıt kapalıdır.',
      'faq.q4': 'Parkio park yeri garanti eder mi?',
      'faq.a4':
        'Hayır. Park müsaitliği hızla değişir. Parkio sürücü kararını desteklemek için kaynak ve tazelik bağlamı sunar; yerin veya tesisin müsait kalacağını garanti edemez.',
      'faq.q5': 'Parkio bir işletmeci veya belediye midir?',
      'faq.a5':
        'Hayır. Parkio bağımsız bir park teknolojisi ürünüdür. Kamuya açık belediye verisini göstermek, açıkça kurulmadıkça resmi ortaklık anlamına gelmez.',
      'faq.q6': 'Parkio nasıl para kazanmayı planlıyor?',
      'faq.a6':
        'Şu an ücretli bir teklif sunulmamaktadır. Parkio, ürün doğrulamasından sonra isteğe bağlı premium kolaylık özellikleri ve gizlilik bilincine sahip toplulaştırılmış park zekâsı hizmetlerini değerlendiriyor.',
      'faq.q7': 'Nasıl iletişim kurabilirim?',
      'faq.a7':
        'Ürün, iş, gizlilik, veri kaynağı veya teknik sorular için info@parkio.dev adresine yazın.',
      'faq.q8': 'Bildirim listesinden nasıl çıkarım?',
      'faq.a8':
        'Onay e-postasındaki çıkış bağlantısını kullanın veya silme talebi için info@parkio.dev yazın. Analitik onayı bu listeden ayrıdır; bu sitede analitik etkin değildir.',
      'footer.copy':
        'Kurucu liderliğinde park keşfi; canlı anonim Explore. Kayıt kapalıdır. İsterseniz kayıt bildirimi için e-posta bırakabilirsiniz.',
      'footer.nav': 'Alt bilgi navigasyonu',
      'footer.rights': '© 2026 Parkio. Tüm hakları saklıdır.',
    },
    en: {
      'meta.title': 'Parkio | Parking Discovery and Intelligence',
      'meta.description':
        'Parkio helps drivers discover parking using community and municipal parking information. Explore a real read-only map at app.parkio.dev/explore — no account required.',
      'skip': 'Skip to content',
      'nav.aria': 'Primary navigation',
      'nav.product': 'Product',
      'nav.how': 'How it works',
      'nav.trust': 'Trust',
      'nav.business': 'Business',
      'nav.roadmap': 'Roadmap',
      'nav.founder': 'Founder',
      'nav.waitlist': 'Waitlist',
      'brand.tagline': 'Parking intelligence',
      'cta.explore': 'Explore parking',
      'cta.waitlist': 'Join the waitlist',
      'cta.signin': 'Sign in',
      'cta.context':
        'Joining the waitlist does not create an app account. Registration remains closed; existing users can sign in.',
      'lang.aria': 'Language',
      'lang.tr': 'TR',
      'lang.en': 'EN',
      'hero.eyebrow': 'Live product · read-only Explore',
      'hero.h1.before': 'Parking discovery with',
      'hero.h1.accent': 'clearer source context.',
      'hero.lede':
        'Parkio helps drivers discover parking using community information and available municipal parking data. A real read-only Explore experience is available now — no account required.',
      'proof.explore.label': 'Explore',
      'proof.explore.value': 'Anonymous · read-only',
      'proof.data.label': 'Data',
      'proof.data.value': 'Source-labelled',
      'proof.reg.label': 'Registration',
      'proof.reg.value': 'Closed',
      'status.pill': 'Live product surface',
      'status.kicker': 'Product evidence',
      'status.title': 'Open Explore without an account',
      'status.body':
        'Parkio’s public Explore map shows real facilities with municipal attribution and truthful freshness. Availability is shown when current; stale occupancy is not presented as live inventory.',
      'status.web': 'Web product',
      'status.web.v': 'Operational',
      'status.muni': 'Municipal discovery',
      'status.muni.v': 'Live (bounded)',
      'status.public': 'Public read-only Explore',
      'status.public.v': 'Live · no account required',
      'status.account': 'Account registration',
      'status.account.v': 'Closed and controlled',
      'status.note':
        'app.parkio.dev/explore is the live product route — not a marketing illustration or closed registration funnel.',
      'product.kicker': 'Product',
      'product.h2': 'A real parking product you can open today.',
      'product.intro':
        'Parkio brings community and municipal parking signals together while keeping source, freshness, and access boundaries clear. Anonymous Explore is live; account registration remains closed.',
      'product.f1.h': 'Discover parking',
      'product.f1.p': 'Review parking facilities and available context through a focused map experience.',
      'product.f2.h': 'Municipal parking data',
      'product.f2.p':
        'Supported municipal facility data is published through a dedicated, bounded read-only contract with clear attribution.',
      'product.f3.h': 'Source-labelled availability',
      'product.f3.p':
        'Human-readable source and attribution accompany facility data. Availability appears when the product considers it suitable to show.',
      'product.f4.h': 'Controlled access',
      'product.f4.p':
        'Read-only public discovery is separate from authenticated contribution and personal parking features.',
      'how.kicker': 'How it works',
      'how.h2': 'A clear parking decision flow.',
      'how.intro':
        'Parkio is designed to reduce uncertainty without promising that changing real-world availability will remain fixed.',
      'how.s1.h': 'Discover',
      'how.s1.p': 'Open Explore and review supported parking information in one focused experience.',
      'how.s2.h': 'Understand',
      'how.s2.p':
        'Use facility, availability when current, freshness, and source context to interpret what is shown.',
      'how.s3.h': 'Decide',
      'how.s3.p': 'Choose the option that fits your trip while accounting for changing local conditions.',
      'how.s4.h': 'Use responsibly',
      'how.s4.p': 'Always follow road signs, local rules, payment requirements, and conditions at the location.',
      'trust.kicker': 'Data & privacy',
      'trust.h2': 'Useful information, clearly sourced.',
      'trust.intro':
        'Parkio combines community-sourced information with available municipal or public parking data. It does not own municipal datasets and does not claim universal real-time occupancy.',
      'trust.notice':
        'Important: Parking availability can change quickly. Parkio provides decision support, not a guarantee that a space will still be available when you arrive.',
      'trust.r1.h': 'Source-aware',
      'trust.r1.p': 'Published municipal data includes a readable source label and attribution.',
      'trust.r2.h': 'Freshness matters',
      'trust.r2.p':
        "Availability is withheld or marked stale when the product's publication policy no longer considers it suitable to show as current.",
      'trust.r3.h': 'Bounded publication',
      'trust.r3.p': 'Public Explore is read-only, geographically bounded, and intentionally limited.',
      'trust.r4.h': 'No implied partnership',
      'trust.r4.p':
        'Displaying public municipal data does not by itself imply partnership, endorsement, or official affiliation.',
      'today.kicker': 'Parkio today',
      'today.h2': 'A working product with live Explore.',
      'today.intro':
        'Parkio’s production infrastructure and web product are operational. Anonymous read-only Explore is live. Account registration remains closed; authenticated contribution and personal workflows stay behind sign-in.',
      'today.l1': 'Production infrastructure is live.',
      'today.l2': 'Anonymous read-only Explore is available without an account.',
      'today.l3': 'Municipal parking discovery is published with attribution.',
      'today.l4': 'Published availability carries source and freshness context.',
      'today.l5': 'Account registration remains closed.',
      'waitlist.kicker': 'Waitlist',
      'waitlist.h2': 'Get notified when registration opens.',
      'waitlist.intro':
        'Leave only your email — no account is created. Purpose: notify you when registrations become available. You are not silently subscribed to unrelated marketing.',
      'waitlist.email': 'Email address',
      'waitlist.consent':
        'I agree that Parkio may email me when registrations open. I will confirm via the link in my email.',
      'waitlist.submit': 'Join the waitlist',
      'waitlist.submitting': 'Submitting…',
      'waitlist.success':
        'Thanks. Your address was received. Open the confirmation link in your email and press Confirm to stay on the list.',
      'waitlist.error.invalid': 'Enter a valid email address.',
      'waitlist.error.consent': 'Please accept the notification consent to continue.',
      'waitlist.error.consentTime':
        'Consent time is invalid or your device clock is too far off. Check your clock and try again.',
      'waitlist.error.network': 'Network error. Please try again.',
      'waitlist.error.rate': 'Too many attempts. Please try again later.',
      'waitlist.error.delivery':
        'Your signup was saved but the confirmation email could not be sent. Please try again shortly.',
      'waitlist.error.generic': 'Could not save right now. Please try again.',
      'waitlist.privacy': 'Privacy policy',
      'waitlist.withdraw': 'To withdraw or request deletion, use the link in the confirmation email or contact info@parkio.dev.',
      'waitlist.mockNote': 'Local isolated mode: durable provider is NOT_EXECUTED.',
      'waitlist.unavailable':
        'The registration notification list is not open yet. If you have a confirmation or withdrawal link, use that page.',
      'business.kicker': 'Business',
      'business.h2': 'How Parkio works as a business',
      'business.intro':
        'The business model is being validated alongside the product. Future directions below are hypotheses, not offers currently on sale.',
      'business.c1.l': 'Today',
      'business.c1.h': 'Founder-led and self-funded',
      'business.c1.p':
        'Parkio operates a live product with closed registration. No revenue, paid conversion, or commercial partnerships are currently documented.',
      'business.c2.l': 'Core product',
      'business.c2.h': 'Accessible parking discovery',
      'business.c2.p':
        'Parkio builds parking discovery and parking intelligence for everyday drivers. Anonymous Explore keeps discovery accessible while the product is validated with real usage.',
      'business.c3.l': 'Future direction',
      'business.c3.h': 'Optional convenience and intelligence',
      'business.c3.p':
        'Parkio is evaluating optional premium convenience features and aggregated parking-intelligence services for operators, municipalities, and mobility businesses.',
      'business.c4.l': 'Privacy principle',
      'business.c4.h': 'Product value, not surveillance',
      'business.c4.p':
        'Parkio does not plan to monetize individual location history, raw movement data, or user identities.',
      'business.note': 'These commercial directions remain under evaluation and are not products currently on sale.',
      'roadmap.kicker': 'Roadmap',
      'roadmap.h2': 'Validate first, expand carefully.',
      'roadmap.intro':
        "Parkio's roadmap is intentionally undated and subject to product, operational, and data-rights evidence.",
      'roadmap.now': 'Now',
      'roadmap.now.h': 'Live Explore, careful access',
      'roadmap.now.p':
        'Operate anonymous read-only discovery, improve community contribution quality, and keep registration closed while validating real usage.',
      'roadmap.next': 'Next',
      'roadmap.next.h': 'Broader product coverage',
      'roadmap.next.p':
        'Expand the mobile experience, personal parking workflows, and broader verified parking coverage after evidence supports it.',
      'roadmap.later': 'Later',
      'roadmap.later.h': 'Resilience and integrations',
      'roadmap.later.p':
        'Strengthen operational scalability, then evaluate additional operator and municipality data integrations after technical and data-rights review.',
      'about.kicker': 'Founder',
      'about.h2': 'Founder-led from product to operations.',
      'about.intro': 'Parkio is an independent, self-funded mobility startup based in Türkiye.',
      'about.founder.l': 'Founder',
      'about.founder.p':
        'Founder of Parkio, responsible for shaping the product, technical foundation, and operational direction.',
      'about.founder.link': 'Oğuzhan Taşyaran on LinkedIn',
      'about.contact.kicker': 'Contact',
      'about.contact.h': 'Contact Parkio',
      'about.contact.p':
        "For product, data-source, business, privacy, or technical inquiries, use Parkio's public business email.",
      'faq.kicker': 'FAQ',
      'faq.h2': 'Clear answers about the current stage.',
      'faq.q1': 'What is Parkio?',
      'faq.a1':
        'Parkio is a parking discovery and intelligence product that combines community information with available municipal parking data in a focused web experience.',
      'faq.q2': 'What is live today?',
      'faq.a2':
        'Parkio’s production infrastructure and web product are live. Anonymous read-only Explore is available at app.parkio.dev/explore without creating an account. Registration remains closed and controlled.',
      'faq.q3': 'Do I need an account to try Parkio?',
      'faq.a3':
        'No. Explore is available without an account. Sign in is only required for authenticated personal and contribution features. New public registration is closed.',
      'faq.q4': 'Does Parkio guarantee a parking space?',
      'faq.a4':
        "No. Parking availability changes quickly. Parkio provides source and freshness context to support a driver's decision, but it cannot guarantee that a space or facility will remain available.",
      'faq.q5': 'Is Parkio a parking operator or municipality?',
      'faq.a5':
        'No. Parkio is an independent parking technology product. Displaying public municipal data does not imply an official partnership unless one is explicitly established.',
      'faq.q6': 'How does Parkio plan to make money?',
      'faq.a6':
        'No paid offer is currently presented. Parkio is evaluating optional premium convenience features and aggregated, privacy-conscious parking-intelligence services after product validation.',
      'faq.q7': 'How can I contact Parkio?',
      'faq.a7':
        'Email info@parkio.dev for product, business, privacy, data-source, or technical inquiries.',
      'faq.q8': 'How do I leave the notification list?',
      'faq.a8':
        'Use the withdraw link in the confirmation email, or email info@parkio.dev to request deletion. Analytics consent is separate; analytics is not active on this site.',
      'footer.copy':
        'Founder-led parking discovery with live anonymous Explore. Registration remains closed. You may leave an email for registration updates.',
      'footer.nav': 'Footer navigation',
      'footer.rights': '© 2026 Parkio. All rights reserved.',
    },
  };

  function resolveLocale() {
    try {
      const saved = localStorage.getItem(STORAGE_KEY);
      if (saved === 'en' || saved === 'tr') return saved;
    } catch (_) {
      /* ignore */
    }
    return 'tr';
  }

  function t(locale, key) {
    return (DICT[locale] && DICT[locale][key]) || (DICT.tr && DICT.tr[key]) || key;
  }

  function applyLocale(locale) {
    const lang = locale === 'en' ? 'en' : 'tr';
    document.documentElement.lang = lang;
    document.querySelectorAll('[data-i18n]').forEach((el) => {
      const key = el.getAttribute('data-i18n');
      const value = t(lang, key);
      if (el.dataset.i18nAttr) {
        el.setAttribute(el.dataset.i18nAttr, value);
      } else {
        el.textContent = value;
      }
    });
    const title = t(lang, 'meta.title');
    const desc = t(lang, 'meta.description');
    document.title = title;
    const metaDesc = document.querySelector('meta[name="description"]');
    if (metaDesc) metaDesc.setAttribute('content', desc);
    document.querySelectorAll('[data-lang-option]').forEach((btn) => {
      const active = btn.getAttribute('data-lang-option') === lang;
      btn.setAttribute('aria-pressed', active ? 'true' : 'false');
      btn.classList.toggle('is-active', active);
    });
    try {
      localStorage.setItem(STORAGE_KEY, lang);
    } catch (_) {
      /* ignore */
    }
    document.dispatchEvent(new CustomEvent('parkio:locale', { detail: { locale: lang } }));
  }

  function init() {
    const locale = resolveLocale();
    applyLocale(locale);
    document.querySelectorAll('[data-lang-option]').forEach((btn) => {
      btn.addEventListener('click', () => applyLocale(btn.getAttribute('data-lang-option')));
    });
  }

  global.ParkioI18n = { DICT, STORAGE_KEY, resolveLocale, applyLocale, t, init };
})(typeof window !== 'undefined' ? window : globalThis);
