import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  useInfiniteQuery,
  useQueryClient,
  type InfiniteData,
} from '@tanstack/react-query';
import { ConflictError } from '@parkio/api-client';
import type { ParkingSessionHistoryResponse, ParkingSessionResponse } from '@parkio/types';
import { parkingKeys } from '@/data/keys';
import {
  PARKING_SESSION_HISTORY_PAGE_SIZE,
  activeParkingSessionQueryOptions,
  parkingSessionHistoryInfiniteQueryOptions,
} from '@/data/query-options/parking';
import { useOnlineStatus } from '@/hooks/useOnlineStatus';
import { useT } from '@/i18n/LocaleProvider';
import { useToast } from '@/providers/ToastProvider';
import { parkingApi } from '@/services/api';
import { useAuthStore } from '@/state/authStore';
import { filterTerminalHistoryItems } from './parkingHistoryModel';

export type ParkingHistoryDeletePhase = 'idle' | 'deletingRow' | 'deletingAll';

function flattenTerminalPages(
  data: InfiniteData<ParkingSessionHistoryResponse> | undefined,
): ParkingSessionResponse[] {
  if (!data) {
    return [];
  }
  return filterTerminalHistoryItems(data.pages.flatMap((page) => page.items));
}

function removeSessionFromHistoryData(
  data: InfiniteData<ParkingSessionHistoryResponse> | undefined,
  sessionId: string,
): InfiniteData<ParkingSessionHistoryResponse> | undefined {
  if (!data) {
    return data;
  }
  return {
    pageParams: data.pageParams,
    pages: data.pages.map((page) => ({
      ...page,
      items: page.items.filter((item) => item.id !== sessionId),
    })),
  };
}

/**
 * Terminal ParkingSession history + owner-safe deletion (S1-P0-11).
 * Online-only mutations; no optimistic removal; ACTIVE query never cleared on success.
 */
export function useParkingSessionHistory(size: number = PARKING_SESSION_HISTORY_PAGE_SIZE) {
  const online = useOnlineStatus();
  const userId = useAuthStore((s) => s.user?.id ?? null);
  const sessionEpoch = useAuthStore((s) => s.sessionEpoch);
  const authenticated = Boolean(userId);

  const query = useInfiniteQuery({
    ...parkingSessionHistoryInfiniteQueryOptions(size),
    enabled: authenticated,
  });

  const items = useMemo(() => flattenTerminalPages(query.data), [query.data]);

  const fetchNextPage = useCallback(() => {
    if (!query.hasNextPage || query.isFetchingNextPage) {
      return;
    }
    void query.fetchNextPage();
  }, [query]);

  const refetch = useCallback(() => query.refetch(), [query]);

  return {
    query,
    items,
    authenticated,
    online,
    userId,
    sessionEpoch,
    size,
    hasNextPage: Boolean(query.hasNextPage),
    isFetchingNextPage: query.isFetchingNextPage,
    fetchNextPage,
    refetch,
  };
}

/**
 * Single-row and full-history deletion mutations with user/session isolation.
 */
export function useDeleteParkingSessionActions(size: number = PARKING_SESSION_HISTORY_PAGE_SIZE) {
  const history = useParkingSessionHistory(size);
  const t = useT();
  const toast = useToast();
  const queryClient = useQueryClient();
  const { online, userId, sessionEpoch, items } = history;

  const [phase, setPhase] = useState<ParkingHistoryDeletePhase>('idle');
  const [pendingRowId, setPendingRowId] = useState<string | null>(null);
  const phaseRef = useRef<ParkingHistoryDeletePhase>('idle');
  const identityKey = `${userId ?? 'anon'}:${sessionEpoch}`;
  const identityRef = useRef(identityKey);

  useEffect(() => {
    identityRef.current = identityKey;
  }, [identityKey]);

  const stillSameUser = useCallback(
    (startedUserId: string | null, startedEpoch: number) => {
      const state = useAuthStore.getState();
      return (state.user?.id ?? null) === startedUserId && state.sessionEpoch === startedEpoch;
    },
    [],
  );

  const invalidateHistory = useCallback(() => {
    void queryClient.invalidateQueries({ queryKey: parkingKeys.sessionHistoryRoot() });
  }, [queryClient]);

  const reconcileActiveConflict = useCallback(async () => {
    await queryClient.invalidateQueries({ queryKey: parkingKeys.activeSession() });
    await queryClient.fetchQuery(activeParkingSessionQueryOptions());
    invalidateHistory();
  }, [invalidateHistory, queryClient]);

  const deleteSession = useCallback(
    async (sessionId: string) => {
      if (phaseRef.current !== 'idle') {
        return;
      }
      if (!online) {
        toast.show(t('parkingSession.history.offline'), 'error');
        return;
      }

      const startedUserId = userId;
      const startedEpoch = sessionEpoch;
      const identityAtStart = `${startedUserId ?? 'anon'}:${startedEpoch}`;

      phaseRef.current = 'deletingRow';
      setPhase('deletingRow');
      setPendingRowId(sessionId);

      try {
        await parkingApi.deleteParkingSession(sessionId);

        if (!stillSameUser(startedUserId, startedEpoch) || identityRef.current !== identityAtStart) {
          return;
        }

        queryClient.setQueriesData<InfiniteData<ParkingSessionHistoryResponse>>(
          { queryKey: parkingKeys.sessionHistoryRoot() },
          (current) => removeSessionFromHistoryData(current, sessionId),
        );
        // 204 already confirmed — update cache only; avoid immediate refetch restoring the row.
        toast.show(t('parkingSession.history.delete.success'), 'success');
      } catch (error) {
        if (!stillSameUser(startedUserId, startedEpoch) || identityRef.current !== identityAtStart) {
          return;
        }
        if (error instanceof ConflictError) {
          await reconcileActiveConflict();
          toast.show(t('parkingSession.history.delete.conflict'), 'error');
          return;
        }
        toast.show(t('parkingSession.history.delete.failed'), 'error');
      } finally {
        if (identityRef.current === identityAtStart) {
          phaseRef.current = 'idle';
          setPhase('idle');
          setPendingRowId(null);
        }
      }
    },
    [
      online,
      queryClient,
      reconcileActiveConflict,
      sessionEpoch,
      stillSameUser,
      t,
      toast,
      userId,
    ],
  );

  const deleteAllHistory = useCallback(async () => {
    if (phaseRef.current !== 'idle') {
      return;
    }
    if (!online) {
      toast.show(t('parkingSession.history.offline'), 'error');
      return;
    }

    const startedUserId = userId;
    const startedEpoch = sessionEpoch;
    const identityAtStart = `${startedUserId ?? 'anon'}:${startedEpoch}`;
    const activeSnapshot = queryClient.getQueryData(parkingKeys.activeSession());

    phaseRef.current = 'deletingAll';
    setPhase('deletingAll');

    try {
      await parkingApi.deleteParkingSessionHistory();

      if (!stillSameUser(startedUserId, startedEpoch) || identityRef.current !== identityAtStart) {
        return;
      }

      queryClient.setQueriesData<InfiniteData<ParkingSessionHistoryResponse>>(
        { queryKey: parkingKeys.sessionHistoryRoot() },
        () => ({
          pageParams: [undefined],
          pages: [{ items: [], nextCursor: null }],
        }),
      );

      // ACTIVE must remain untouched even if a mistaken wipe occurred.
      if (activeSnapshot !== undefined) {
        const activeAfter = queryClient.getQueryData(parkingKeys.activeSession());
        if (activeAfter === undefined) {
          queryClient.setQueryData(parkingKeys.activeSession(), activeSnapshot);
        }
      }

      toast.show(t('parkingSession.history.deleteAll.success'), 'success');
    } catch {
      if (!stillSameUser(startedUserId, startedEpoch) || identityRef.current !== identityAtStart) {
        return;
      }
      toast.show(t('parkingSession.history.deleteAll.failed'), 'error');
    } finally {
      if (identityRef.current === identityAtStart) {
        phaseRef.current = 'idle';
        setPhase('idle');
      }
    }
  }, [online, queryClient, sessionEpoch, stillSameUser, t, toast, userId]);

  const busy = phase !== 'idle';

  return {
    ...history,
    phase,
    pendingRowId,
    busy,
    rowDeleteDisabled: busy,
    deleteAllDisabled: busy || items.length === 0,
    deleteSession,
    deleteAllHistory,
  };
}
