import { describe, expect, it } from 'vitest';
import i18n from 'i18next';
import { getStaffNavItems } from './navConfig';

describe('getStaffNavItems', () => {
  const t = i18n.t.bind(i18n);

  it('returns no staff links for ordinary users', () => {
    expect(getStaffNavItems(['USER'], t)).toEqual([]);
  });

  it('returns moderation only for moderators', () => {
    expect(getStaffNavItems(['MODERATOR'], t).map((item) => item.to)).toEqual(['/admin/moderation']);
  });

  it('returns moderation and admin for admins', () => {
    expect(getStaffNavItems(['ADMIN'], t).map((item) => item.to)).toEqual([
      '/admin/moderation',
      '/admin',
    ]);
  });
});
