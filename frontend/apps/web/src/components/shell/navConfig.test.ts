import { describe, expect, it } from 'vitest';
import { getStaffNavItems } from './navConfig';

describe('getStaffNavItems', () => {
  it('returns no staff links for ordinary users', () => {
    expect(getStaffNavItems(['USER'])).toEqual([]);
  });

  it('returns moderation only for moderators', () => {
    expect(getStaffNavItems(['MODERATOR']).map((item) => item.to)).toEqual(['/admin/moderation']);
  });

  it('returns moderation and admin for admins', () => {
    expect(getStaffNavItems(['ADMIN']).map((item) => item.to)).toEqual(['/admin/moderation', '/admin']);
  });
});