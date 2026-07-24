import { QueryClient } from '@tanstack/react-query';
import { clearUserSessionQueries } from '../sessionQueryCache';
import { meKeys, parkingKeys, reportsKeys } from '../keys';

describe('WP-07 session query cache isolation', () => {
  it('removes user-scoped roots and preserves nearby discovery', () => {
    const client = new QueryClient();
    const remove = jest.spyOn(client, 'removeQueries');
    const cancel = jest.spyOn(client, 'cancelQueries');

    client.setQueryData(meKeys.profile(), { id: 'u1' });
    client.setQueryData(parkingKeys.mySpots(), []);
    client.setQueryData(reportsKeys.all, []);
    client.setQueryData(parkingKeys.nearby({ lat: 1, lng: 2 }), []);

    clearUserSessionQueries(client);

    expect(cancel).toHaveBeenCalled();
    expect(remove).toHaveBeenCalledWith({ queryKey: meKeys.all });
    expect(remove).toHaveBeenCalledWith({ queryKey: parkingKeys.mySpots() });
    expect(remove).toHaveBeenCalledWith({ queryKey: reportsKeys.all });
    expect(client.getQueryData(parkingKeys.nearby({ lat: 1, lng: 2 }))).toEqual([]);
  });
});