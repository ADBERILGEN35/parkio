import type { QueryClient } from '@tanstack/react-query';
import {
  gamificationKeys,
  meKeys,
  notificationsKeys,
} from '@/data/keys';

/**
 * Marks every query derived from gamification events stale after an action that
 * awards or deducts points/trust (spot create, verify, claim). The backend updates
 * these asynchronously (Kafka), so instead of optimistic writes we invalidate:
 * active screens refetch immediately and inactive ones refetch on next visit,
 * which prevents stale points/level/trust/notifications from being shown.
 */
export function invalidateGamificationQueries(queryClient: QueryClient): Promise<void> {
  return Promise.all([
    queryClient.invalidateQueries({ queryKey: meKeys.stats() }),
    queryClient.invalidateQueries({ queryKey: gamificationKeys.points() }),
    queryClient.invalidateQueries({ queryKey: gamificationKeys.level() }),
    queryClient.invalidateQueries({ queryKey: gamificationKeys.progress() }),
    queryClient.invalidateQueries({ queryKey: gamificationKeys.accessPolicy() }),
    queryClient.invalidateQueries({ queryKey: gamificationKeys.leaderboard() }),
    queryClient.invalidateQueries({ queryKey: notificationsKeys.all }),
  ]).then(() => undefined);
}
