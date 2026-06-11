export interface FieldError {
  field: string;
  message: string;
}

/** Uniform error body returned by gateway and backend services. */
export interface ApiError {
  code: string;
  message: string;
  traceId: string;
  timestamp: string;
  fieldErrors?: FieldError[];
}

export type ApiErrorCode =
  | 'ACCOUNT_NOT_ACTIVE'
  | 'FORBIDDEN'
  | 'USER_STATUS_UNAVAILABLE'
  | 'MISSING_TOKEN'
  | 'INVALID_TOKEN'
  | string;
