import { translate } from '../translations';

describe('mobile translate', () => {
  it('defaults templates to Turkish', () => {
    expect(translate('tr', 'Welcome back')).toBe('Tekrar hoş geldiniz');
    expect(translate('tr', 'Find parking')).toBe('Park yeri bul');
    expect(translate('tr', 'Leaderboard')).toBe('Sıralama');
    expect(translate('tr', 'My spots')).toBe('Yerlerim');
    expect(translate('tr', 'Good (19 m)')).toBe('İyi (19 m)');
    expect(translate('tr', '2 spots nearby')).toBe('Yakında 2 yer');
    expect(translate('tr', 'You earned 5 points. Total: 10.')).toBe('5 puan kazandınız. Toplam: 10.');
    expect(translate('tr', 'Uploading… 0%')).toBe('Yükleniyor… %0');
  });

  it('passes English through unchanged', () => {
    expect(translate('en', 'Welcome back')).toBe('Welcome back');
    expect(translate('en', 'Good (19 m)')).toBe('Good (19 m)');
  });
});