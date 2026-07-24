import type { AxiosError } from 'axios';
import { toParkioError } from './sdk-errors';

export * from './sdk-errors';

/** Axios-specific compatibility adapter for the existing client. */
export function getAxiosParkioError(error: AxiosError) {
  const status = error.response?.status ?? 500;
  return toParkioError(status, error.response?.data, { cause: error });
}
