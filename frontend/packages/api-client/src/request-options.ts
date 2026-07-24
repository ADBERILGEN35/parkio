/**
 * Optional per-request transport controls shared by read APIs.
 * Accepting an options bag (rather than a bare AbortSignal) keeps
 * `queryFn: api.method` TanStack pass-throughs safe: QueryFunctionContext
 * already exposes `.signal`, so bare method references forward cancellation.
 */
export type RequestOptions = {
  signal?: AbortSignal;
};
