import { localizeNotification } from '../localizeNotification';

describe('localizeNotification', () => {
  it('localizes POINT_EARNED from metadata', () => {
    const copy = localizeNotification(
      {
        id: '1',
        type: 'POINT_EARNED',
        title: 'You earned points',
        body: 'You earned 5 points. Total: 5.',
        createdAt: new Date().toISOString(),
        readAt: null,
        metadata: { points: '5', totalPoints: '10', messageKey: 'pointEarned' },
      } as any,
      (s) => {
        if (s.startsWith('You earned 5 points')) return '5 puan kazandınız. Toplam: 10.';
        if (s === 'You earned points') return 'Puan kazandınız';
        if (s === 'Point earned') return 'Puan kazanıldı';
        return s;
      },
    );
    expect(copy.title).toBe('Puan kazandınız');
    expect(copy.body).toContain('10');
  });

  it('localizes trustChanged from messageKey', () => {
    const copy = localizeNotification(
      {
        id: '3',
        type: 'WARNING',
        title: 'Dikkat',
        body: 'Güven puanınız 100 değerinden 85 değerine düştü.',
        createdAt: new Date().toISOString(),
        readAt: null,
        metadata: {
          messageKey: 'trustChanged',
          previousScore: '100',
          newScore: '85',
          direction: 'decreased',
        },
      } as any,
      (s) => s,
    );
    expect(copy.title).toBe('Heads up');
    expect(copy.body).toBe('Your trust score decreased from 100 to 85.');
  });

  it('falls back to stored title/body for legacy rows', () => {
    const copy = localizeNotification(
      {
        id: '2',
        type: 'SYSTEM',
        title: 'Legacy English title',
        body: 'Legacy English body',
        createdAt: new Date().toISOString(),
        readAt: null,
        metadata: {},
      } as any,
      (v) => v,
    );
    expect(copy.title).toBe('Legacy English title');
    expect(copy.body).toBe('Legacy English body');
  });
});
