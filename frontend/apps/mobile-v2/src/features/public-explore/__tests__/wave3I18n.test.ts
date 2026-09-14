import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const TR_SLICE = readFileSync(
  join(__dirname, '../../../i18n/translations.ts'),
  'utf8',
);

describe('Wave 3 i18n keys', () => {
  const required = [
    'publicExplore.preview.viewDetails',
    'publicExplore.teaser.municipalHidden',
    'publicExplore.teaser.community',
    'publicExplore.contribute.cta',
    'publicExplore.contribute.support',
    'authGate.login',
    'authGate.dismiss',
    'authGate.facilityDetail.title',
    'authGate.facilityDetail.body',
    'authGate.municipalHidden.title',
    'authGate.municipalHidden.body',
    'authGate.community.title',
    'authGate.community.body',
    'authGate.contribute.title',
    'authGate.contribute.body',
  ];

  it('defines TR and EN membership copy keys', () => {
    for (const key of required) {
      const occurrences = TR_SLICE.split(`'${key}'`).length - 1;
      expect(occurrences).toBeGreaterThanOrEqual(2);
    }
  });

  it('uses required TR product phrases', () => {
    expect(TR_SLICE).toContain("'Tesis detayını gör'");
    expect(TR_SLICE).toContain("'+{count} otopark daha'");
    expect(TR_SLICE).toContain("'Bu bölgede {count} topluluk park noktası var'");
    expect(TR_SLICE).toContain("'Park yeri bildir'");
    expect(TR_SLICE).toContain("'Topluluğa katkıda bulun'");
    expect(TR_SLICE).toContain("'Giriş yap'");
    expect(TR_SLICE).toContain("'Şimdi değil'");
    expect(TR_SLICE).toContain("'Tüm park seçeneklerini görün'");
    expect(TR_SLICE).toContain("'Topluluk park noktaları'");
    expect(TR_SLICE).toContain('topluluğun bildirdiği noktaları görebilirsiniz');
  });
});
