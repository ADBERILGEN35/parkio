import { useQuery } from '@tanstack/react-query';
import { useParkioSdk } from '@/app/AppRuntimeContext';
import { myReportsQueryOptions } from '@/data/query-options/reports';

export function useMyReportsQuery() {
  const sdk = useParkioSdk();
  return useQuery(myReportsQueryOptions(sdk));
}