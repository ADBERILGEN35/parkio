import type { ParkioLocale } from '@parkio/types';

/**
 * Mobile copy translations keyed by English source copy.
 * Turkish is the canonical default; English is first-class via Settings.
 */
const tr: Record<string, string> = {
  'Your photo is being checked. You can track status in My spots. It appears on the map only after validation passes.': 'Fotoğrafınız kontrol ediliyor. Durumu Yerlerim ekranından takip edebilirsiniz. Doğrulama geçtikten sonra haritada görünür.',
  'Spot submitted': 'Park yeri gönderildi',
  Rejected: 'Reddedildi',
  Expired: 'Süresi dolmuş',
  Filled: 'Dolu',
  Unconfirmed: 'Onaylanmamış',
  Verified: 'Doğrulandı',
  Active: 'Aktif',
  Removed: 'Kaldırıldı',
  'Under review': 'İncelemede',
  Validating: 'Doğrulanıyor',
  Home: 'Ana Sayfa',
  Map: 'Harita',
  'My spots': 'Yerlerim',
  Share: 'Paylaş',
  Leaderboard: 'Sıralama',
  Notifications: 'Bildirimler',
  Profile: 'Profil',
  More: 'Daha fazla',
  Impact: 'Katkılarım',
  'Your Impact': 'Katkılarınız',
  'Find parking': 'Park yeri bul',
  'Share a spot': 'Park yeri paylaş',
  'Share spot': 'Park yerini paylaş',
  Retry: 'Tekrar dene',
  Cancel: 'İptal',
  Done: 'Tamam',
  Save: 'Kaydet',
  Continue: 'Devam et',
  Back: 'Geri',
  On: 'Açık',
  Off: 'Kapalı',
  Yes: 'Evet',
  No: 'Hayır',
  Close: 'Kapat',
  'Loading…': 'Yükleniyor…',
  'Something went wrong': 'Bir şeyler ters gitti',
  'You are offline': 'Çevrimdışısınız',
  'Check your connection and try again.': 'Bağlantınızı kontrol edip tekrar deneyin.',
  Email: 'E-posta',
  Password: 'Şifre',
  'Sign in': 'Giriş yap',
  'Sign up': 'Kayıt ol',
  'Create account': 'Hesap oluştur',
  'Create an account': 'Hesap oluştur',
  'Forgot password?': 'Şifrenizi mi unuttunuz?',
  'Back to sign in': 'Girişe dön',
  'Welcome back': 'Tekrar hoş geldiniz',
  'Sign in to find and share parking.': 'Otopark bulmak ve paylaşmak için giriş yapın.',
  'New to Parkio?': 'Parkio’da yeni misiniz?',
  'you@example.com': 'ornek@eposta.com',
  'Your password': 'Şifreniz',
  Parkio: 'Parkio',
  'Concierge for the curb.': 'Kaldırım için konsiyerj.',
  'Find and share real parking spots verified in real time by neighbors.': 'Komşuların gerçek zamanlı doğruladığı gerçek otopark yerlerini bulun ve paylaşın.',
  'Reset password': 'Şifreyi sıfırla',
  'New password': 'Yeni şifre',
  'Confirm password': 'Şifreyi doğrula',
  'Confirm new password': 'Yeni şifreyi doğrula',
  'Current password': 'Mevcut şifre',
  'Update password': 'Şifreyi güncelle',
  'Choose a new password': 'Yeni bir şifre seçin',
  'Verify your email': 'E-postanızı doğrulayın',
  'Email verified': 'E-posta doğrulandı',
  'Request a new link': 'Yeni bağlantı iste',
  'Resend verification': 'Doğrulamayı yeniden gönder',
  'Send instructions': 'Talimatları gönder',
  'Checking your link…': 'Bağlantınız kontrol ediliyor…',
  'Your account is ready for sign in.': 'Hesabınız giriş yapmaya hazır.',
  'Verification link is invalid or expired.': 'Doğrulama bağlantısı geçersiz veya süresi dolmuş.',
  'We could not verify this link.': 'Bu bağlantıyı doğrulayamadık.',
  'Show password': 'Şifreyi göster',
  'Hide password': 'Şifreyi gizle',
  Preferences: 'Tercihler',
  'Preferences saved.': 'Tercihler kaydedildi.',
  'Save preferences': 'Tercihleri kaydet',
  Language: 'Dil',
  Turkish: 'Türkçe',
  English: 'English',
  'Search radius and notifications': 'Arama yarıçapı ve bildirimler',
  Account: 'Hesap',
  Activity: 'Etkinlik',
  Parking: 'Park',
  Staff: 'Ekip',
  'Edit profile': 'Profili düzenle',
  'Display name, phone, and city': 'Görünen ad, telefon ve şehir',
  Vehicle: 'Araç',
  'Vehicle type and plate': 'Araç tipi ve plaka',
  'Change password': 'Şifreyi değiştir',
  'Spots you shared': 'Paylaştığınız park yerleri',
  'Top contributors': 'En çok katkı sağlayanlar',
  'Points, levels, and activity history': 'Puanlar, seviyeler ve etkinlik geçmişi',
  'My reports': 'Bildirimlerim',
  'Reports and appeals': 'Raporlar ve itirazlar',
  Moderation: 'Moderasyon',
  'Review cases and appeals': 'Vakaları ve itirazları inceleyin',
  Analytics: 'Analitik',
  'Platform KPIs and metrics': 'Platform KPI ve metrikleri',
  'Log out': 'Çıkış yap',
  'Log out all': 'Tüm oturumları kapat',
  'Log out of all devices': 'Tüm cihazlardan çıkış yap',
  'Log out of all devices?': 'Tüm cihazlardan çıkış yapılsın mı?',
  'This signs you out everywhere and revokes all active sessions.': 'Bu işlem tüm cihazlardaki oturumlarınızı kapatır ve etkin oturumları iptal eder.',
  'Parkio driver': 'Parkio sürücüsü',
  'Couldn’t load your profile': 'Profiliniz yüklenemedi',
  'Couldn’t load preferences': 'Tercihler yüklenemedi',
  'Access denied': 'Erişim reddedildi',
  'This area requires an admin role.': 'Bu alan için yönetici rolü gerekir.',
  'Signed in as': 'Oturum:',
  'About the app': 'Uygulama hakkında',
  'Technical information': 'Teknik bilgiler',
  Version: 'Sürüm',
  Build: 'Derleme',
  Environment: 'Ortam',
  'Copy diagnostics': 'Tanılama bilgisini kopyala',
  'Diagnostics copied.': 'Tanılama bilgisi kopyalandı.',
  'App version and build details for support.': 'Destek için uygulama sürümü ve derleme bilgileri.',
  'Your impact': 'Katkınız',
  'Quick actions': 'Hızlı işlemler',
  Points: 'Puan',
  Level: 'Seviye',
  Trust: 'Güven',
  'High Trust': 'Yüksek güven',
  'Medium Trust': 'Orta güven',
  'Low Trust': 'Düşük güven',
  Driver: 'Sürücü',
  Photo: 'Fotoğraf',
  Location: 'Konum',
  Details: 'Ayrıntılar',
  Review: 'Özet',
  Next: 'İleri',
  'Edit location': 'Konumu düzenle',
  'Edit details': 'Ayrıntıları düzenle',
  'No note': 'Not yok',
  'Share another spot': 'Başka bir park yeri paylaş',
  'Upload photo': 'Fotoğraf yükle',
  'Upload another photo': 'Başka bir fotoğraf yükle',
  'Upload a photo first': 'Önce bir fotoğraf yükleyin',
  'Photo uploaded': 'Fotoğraf yüklendi',
  'Uploading photo': 'Fotoğraf yükleniyor',
  'Upload cancelled': 'Yükleme iptal edildi',
  'Retry upload': 'Yüklemeyi tekrar dene',
  'Choose another': 'Başka bir tane seç',
  'Use this photo': 'Bu fotoğrafı kullan',
  Retake: 'Yeniden çek',
  'Close camera': 'Kamerayı kapat',
  'Switch camera': 'Kamerayı değiştir',
  'Take photo': 'Fotoğraf çek',
  'Preview of the parking spot photo you captured': 'Çektiğiniz park yeri fotoğrafının önizlemesi',
  'Photo of the parking spot': 'Park yerinin fotoğrafı',
  'Media is ready for Spot Creation.': 'Medya park yeri oluşturmaya hazır.',
  'The photo is still prepared on this screen. Retry when ready, or choose another image.': 'Fotoğraf bu ekranda hâlâ hazır. Hazır olduğunuzda tekrar deneyin veya başka bir görsel seçin.',
  'Add a photo of the spot': 'Park yerinin fotoğrafını ekleyin',
  'A clear photo helps other drivers find and trust the spot. Take one now or pick from your gallery.': 'Net bir fotoğraf diğer sürücülerin yeri bulmasına ve güvenmesine yardımcı olur. Şimdi çekin veya galeriden seçin.',
  'Choose from gallery': 'Galeriden seç',
  'Opens the camera to capture a new photo': 'Yeni bir fotoğraf çekmek için kamerayı açar',
  'Opens your photo library to pick an existing photo': 'Mevcut bir fotoğraf seçmek için arşivi açar',
  'Open Settings': 'Ayarları aç',
  'Camera access is off': 'Kamera erişimi kapalı',
  'Gallery access is off': 'Galeri erişimi kapalı',
  'Allow camera access to take a parking spot photo.': 'Park yeri fotoğrafı çekmek için kamera erişimine izin verin.',
  'Allow photo library access to choose an existing parking spot photo.': 'Mevcut bir park yeri fotoğrafını seçmek için fotoğraf arşivi erişimine izin verin.',
  'You are offline. Upload will be ready to retry when the connection returns.': 'Çevrimdışısınız. Bağlantı geri geldiğinde yüklemeyi yeniden deneyebilirsiniz.',
  'Keep Parkio open while the upload finishes. If the connection drops, retry uses the same prepared file.': 'Yükleme bitene kadar Parkio’yu açık tutun. Bağlantı düşerse tekrar deneme aynı hazır dosyayı kullanır.',
  'Keep Parkio open while the upload finishes. If the connection drops, retry uses the same prepared file and idempotency key.': 'Yükleme bitene kadar Parkio’yu açık tutun. Bağlantı düşerse tekrar deneme aynı hazır dosyayı ve isteği kullanır.',
  'Uploading…': 'Yükleniyor…',
  'That photo cannot be uploaded.': 'Bu fotoğraf yüklenemez.',
  'Change photo': 'Fotoğrafı değiştir',
  'Spot created': 'Park yeri oluşturuldu',
  'Spot is live': 'Park yeri yayında',
  'Your spot was submitted and will appear in the map flow for nearby drivers.': 'Park yeriniz gönderildi ve yakındaki sürücülerin harita akışında görünecek.',
  'View map': 'Haritayı görüntüle',
  'Loading map…': 'Harita yükleniyor…',
  'Map failed to load': 'Harita yüklenemedi',
  'Retry map': 'Haritayı tekrar yükle',
  'Vehicle type': 'Araç tipi',
  'Parking type': 'Park tipi',
  Note: 'Not',
  'Optional note': 'İsteğe bağlı not',
  'Optional: entrance, landmarks, restrictions': 'İsteğe bağlı: giriş, işaretler, kısıtlamalar',
  'Discard draft': 'Taslağı sil',
  Any: 'Tümü',
  Sedan: 'Sedan',
  Hatchback: 'Hatchback',
  SUV: 'SUV',
  Van: 'Panelvan',
  Motorcycle: 'Motosiklet',
  Street: 'Sokak',
  'Open lot': 'Açık',
  Indoor: 'Kapalı otopark',
  Mall: 'AVM',
  Residential: 'Yerleşim alanı',
  Office: 'Ofis',
  Unsure: 'Emin değilim',
  'GPS accuracy': 'GPS doğruluğu',
  'Selected area': 'Seçilen alan',
  Good: 'İyi',
  Fair: 'Orta',
  Poor: 'Düşük',
  'GPS accuracy is too low to publish.': 'Yayınlamak için GPS doğruluğu çok düşük.',
  'GPS accuracy is too low. Refresh location or move the marker after a better fix.': 'GPS doğruluğu çok düşük. Konumu yenileyin veya daha iyi bir sinyalden sonra işaretçiyi taşıyın.',
  'Location access is off': 'Konum erişimi kapalı',
  'Enable location': 'Konumu aç',
  'Open app settings': 'Uygulama ayarlarını aç',
  'Search a place or address': 'Sokak, mahalle veya yer arayın',
  'Leave parking spot sharing?': 'Park yeri paylaşımından çıkılsın mı?',
  'You have unsaved changes. If you leave now, your progress will be lost.': 'Kaydedilmemiş değişiklikleriniz var. Şimdi ayrılırsanız ilerlemeniz kaybolur.',
  'You have an unfinished parking spot submission. If you leave now, your progress will be lost.': 'Tamamlanmamış bir park yeri paylaşımınız var. Şimdi ayrılırsanız ilerlemeniz kaybolur.',
  'Unsaved changes': 'Kaydedilmemiş değişiklikler',
  'Continue sharing': 'Paylaşmaya devam et',
  'Leave and discard changes': 'Çık ve değişiklikleri sil',
  Stay: 'Kal',
  Leave: 'Ayrıl',
  'Search this area': 'Bu bölgede ara',
  'Search nearby': 'Yakında ara',
  '1 spot nearby': 'Yakında 1 yer',
  '{count} spots nearby': 'Yakında {count} yer',
  'No spots nearby': 'Yakında park yeri yok',
  'Parking spot': 'Park yeri',
  'Likely available': 'Muhtemelen müsait',
  'Community reported': 'Topluluk bildirimi',
  'Legal parking': 'Yasal park',
  'Uncertain legality': 'Belirsiz yasal durum',
  'Illegal or risky': 'Yasadışı veya riskli',
  'View details': 'Detayları gör',
  'Get directions': 'Yol tarifi al',
  'Updated just now': 'Az önce güncellendi',
  'Updated 1 min ago': '1 dk önce güncellendi',
  'Updated {n} min ago': '{n} dk önce güncellendi',
  'Updated {n} h ago': '{n} sa önce güncellendi',
  'Finding parking…': 'Park yeri aranıyor…',
  'No spots found in this area': 'Bu bölgede park yeri bulunamadı',
  'Use my location': 'Konumumu kullan',
  'No notifications yet': 'Henüz bildirim yok',
  'Mark as read': 'Okundu olarak işaretle',
  'Marking…': 'İşaretleniyor…',
  'You earned points': 'Puan kazandınız',
  'You earned {points} points. Total: {total}.': '{points} puan kazandınız. Toplam: {total}.',
  'Point earned': 'Puan kazanıldı',
  'View impact': 'Katkıyı gör',
  'just now': 'az önce',
  '1 min ago': '1 dk önce',
  '{n} min ago': '{n} dk önce',
  '1 h ago': '1 sa önce',
  '{n} h ago': '{n} sa önce',
  '1 d ago': '1 g önce',
  '{n} d ago': '{n} g önce',
  '2 d ago': '2 g önce',
  'Level up!': 'Seviye atladınız!',
  'Activity on your spots will show up here.': 'Park yerlerinizdeki etkinlik burada görünür.',
  'No spots yet': 'Henüz park yeri yok',
  'Spots you share will appear here, with their live status and verification activity.': 'Paylaştığınız yerler canlı durumları ve doğrulama etkinliğiyle burada görünür.',
  'Share your first spot': 'İlk park yerinizi paylaşın',
  'No reports yet': 'Henüz rapor yok',
  'Spot detail': 'Park yeri detayı',
  Suspicious: 'Şüpheli',
  Unknown: 'Bilinmiyor',
  'Reported filled': 'Dolu bildirildi',
  'Listing expired': 'İlan süresi doldu',
  'Not yet confirmed': 'Henüz onaylanmadı',
  'High confidence': 'Yüksek güven',
  'Low confidence': 'Düşük güven',
  'No confidence': 'Güven yok',
  'Legality uncertain': 'Yasallık belirsiz',
  'Risky / may be illegal': 'Riskli / yasal olmayabilir',
  Excellent: 'Mükemmel',
  Usable: 'Kullanılabilir',
  'Too imprecise': 'Çok belirsiz',
  'Improving location…': 'Konum iyileştiriliyor…',
  'Location permission needed': 'Konum izni gerekli',
  'Parkio uses GPS to place the spot where you stand.': 'Parkio, park yerini bulunduğunuz konuma yerleştirmek için GPS kullanır.',
  Allow: 'İzin ver',
  'Location permission denied': 'Konum izni reddedildi',
  'Enable location for Parkio in system settings.': 'Sistem ayarlarından Parkio için konumu açın.',
  'GPS unavailable': 'GPS kullanılamıyor',
  'Move outside or near a window, then retry.': 'Dışarı veya pencere yakınına çıkıp tekrar deneyin.',
  'Retry GPS': 'GPS\'i yeniden dene',
  'A better fix helps drivers find the spot faster.': 'Daha iyi bir sinyal sürücülerin yeri daha hızlı bulmasına yardımcı olur.',
  Refresh: 'Yenile',
  'Waiting for GPS': 'GPS bekleniyor',
  'Preparing photo…': 'Fotoğraf hazırlanıyor…',
  'Waiting for connection…': 'Bağlantı bekleniyor…',
  'Upload failed.': 'Yükleme başarısız.',
  'Upload progress': 'Yükleme ilerlemesi',
  'Join Parkio to find parking faster.': 'Daha hızlı park yeri bulmak için Parkio\'ya katılın.',
  'Already have an account?': 'Zaten hesabınız var mı?',
  'At least 12 characters': 'En az 12 karakter',
  'One parking check near home, right before you head back.': 'Eve dönmeden hemen önce, evinize yakın tek bir park kontrolü.',
  'Smart Return': 'Akıllı Dönüş',
  'Spot Creation starts after a successful parking photo upload.': 'Park yeri oluşturma, başarılı bir park fotoğrafı yüklemesinden sonra başlar.',
  'Heads up': 'Dikkat',
  'Your parking spot was rejected as illegal or risky.': 'Park yeriniz yasadışı veya riskli bulunduğu için reddedildi.',
  'Your account has been suspended by moderation.': 'Hesabınız moderasyon tarafından askıya alındı.',
  'Your parking spot was rejected by a moderator.': 'Park yeriniz bir moderatör tarafından reddedildi.',
  Update: 'Güncelleme',
  'Your account has been restored.': 'Hesabınız geri yüklendi.',
  'Appeal update': 'İtiraz güncellemesi',
  accepted: 'kabul edildi',
  rejected: 'reddedildi',
  increased: 'yükseldi',
  decreased: 'düştü',
  'A moderation case about your account was resolved.': 'Hesabınızla ilgili bir moderasyon vakası sonuçlandırıldı.',
  'Are you driving today?': 'Bugün araç kullanacak mısınız?',
  'Tell Parkio if you want a parking check before you return.': 'Dönmeden önce park kontrolü isteyip istemediğinizi Parkio\'ya bildirin.',
  'Parking may be available': 'Park yeri müsait olabilir',
  'Parking near your saved home area may be available now.': 'Kayıtlı ev alanınızın yakınında şu an park yeri müsait olabilir.',
  'Point Earned': 'Puan kazanıldı',
  'Dropped pin': 'Bırakılan pin',
  'Finding address…': 'Adres bulunuyor…',
  'Move the map to fine-tune the exact spot.': 'Tam konumu ayarlamak için haritayı kaydırın.',
};

export function translate(locale: ParkioLocale, value: string): string {
  if (locale !== 'tr') return value;
  if (tr[value] !== undefined) return tr[value];

  // Simple template: "{count} spots nearby"
  const nearbyMatch = value.match(/^(\d+) spots nearby$/);
  if (nearbyMatch) return `Yakında ${nearbyMatch[1]} yer`;

  const minMatch = value.match(/^Updated (\d+) min ago$/);
  if (minMatch) return `${minMatch[1]} dk önce güncellendi`;

  const hMatch = value.match(/^Updated (\d+) h ago$/);
  if (hMatch) return `${hMatch[1]} sa önce güncellendi`;

  const agoMin = value.match(/^(\d+) min ago$/);
  if (agoMin) return `${agoMin[1]} dk önce`;

  const agoH = value.match(/^(\d+) h ago$/);
  if (agoH) return `${agoH[1]} sa önce`;

  const agoD = value.match(/^(\d+) d ago$/);
  if (agoD) return `${agoD[1]} g önce`;


  const excellentGps = value.match(/^Excellent \((.+)\)$/);
  if (excellentGps) return `Mükemmel (${excellentGps[1]})`;

  const usableGps = value.match(/^Usable \((.+)\)$/);
  if (usableGps) return `Kullanılabilir (${usableGps[1]})`;

  const impreciseGps = value.match(/^Too imprecise \((.+)\)$/);
  if (impreciseGps) return `Çok belirsiz (${impreciseGps[1]})`;

  const oneSpot = value.match(/^1 spot nearby$/);
  if (oneSpot) return 'Yakında 1 yer';

  const updatedPrefix = value.match(/^Updated (.+)$/);
  if (updatedPrefix) {
    const inner = translate(locale, updatedPrefix[1]);
    if (inner !== updatedPrefix[1]) return inner.includes('güncellendi') || inner.includes('önce') ? (inner.startsWith('Güncellendi') ? inner : inner) : `${inner}`;
    // "Updated just now" / "Updated 5 min ago"
    if (updatedPrefix[1] === 'just now') return 'Az önce güncellendi';
    const m = updatedPrefix[1].match(/^(\d+) min ago$/);
    if (m) return `${m[1]} dk önce güncellendi`;
    const h = updatedPrefix[1].match(/^(\d+) h ago$/);
    if (h) return `${h[1]} sa önce güncellendi`;
    const d = updatedPrefix[1].match(/^(\d+) d ago$/);
    if (d) return `${d[1]} g önce güncellendi`;
  }

  const earnedPts = value.match(/^You earned (\d+) points\. Total: (\d+)\.$/);
  if (earnedPts) return `${earnedPts[1]} puan kazandınız. Toplam: ${earnedPts[2]}.`;

  const lostPts = value.match(/^You lost (\d+) points \(penalty\)\.$/);
  if (lostPts) return `${lostPts[1]} puan kaybettiniz (ceza).`;

  const trustChanged = value.match(
    /^Your trust score (increased|decreased) from (\d+) to (\d+)\.$/,
  );
  if (trustChanged) {
    const direction = trustChanged[1] === 'increased' ? 'yükseldi' : 'düştü';
    return `Güven puanınız ${trustChanged[2]} değerinden ${trustChanged[3]} değerine ${direction}.`;
  }

  const appealResolved = value.match(/^Your appeal was (accepted|rejected)\.$/);
  if (appealResolved) {
    const outcome = appealResolved[1] === 'accepted' ? 'kabul edildi' : 'reddedildi';
    return `İtirazınız ${outcome}.`;
  }

  const levelCongrats = value.match(/^Congratulations — you reached level (\d+)\.$/);
  if (levelCongrats) return `Tebrikler — ${levelCongrats[1]}. seviyeye ulaştınız.`;

  const uploading = value.match(/^Uploading… (\d+)%$/);
  if (uploading) return `Yükleniyor… %${uploading[1]}`;

  // Wizard progress: "Step 2 of 4 · Location" (label may already be translated)
  const wizardProgress = value.match(/^Step (\d+) of (\d+) · (.+)$/);
  if (wizardProgress) return `Adım ${wizardProgress[1]} / ${wizardProgress[2]} · ${wizardProgress[3]}`;

  const mediaReady = value.match(/^Media is ready for Spot Creation\. (.+)$/);
  if (mediaReady) return `Medya park yeri oluşturmaya hazır. ${mediaReady[1]}`;

  const goodGps = value.match(/^Good \((.+)\)$/);
  if (goodGps) return `İyi (${goodGps[1]})`;

  const fairGps = value.match(/^Fair \((.+)\)$/);
  if (fairGps) return `Orta (${fairGps[1]})`;

  const poorGps = value.match(/^Poor \((.+)\)$/);
  if (poorGps) return `Düşük (${poorGps[1]})`;

  return value;
}
