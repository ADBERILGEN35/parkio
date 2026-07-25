import type { ParkioApiError } from '@parkio/api-client';
import i18n from 'i18next';

/** Friendly wording for expected report failures; null falls back to FriendlyApiErrorMessage. */
export function mapParkingReportError(error: ParkioApiError): string | null {
  if (error.status === 409 && error.code === 'DUPLICATE_REPORT') {
    return i18n.t('parking:spotDetail.duplicateReport');
  }
  if (error.status === 404) {
    return i18n.t('parking:spotDetail.spotGone');
  }
  return null;
}

/** Friendly wording for expected verify/claim failures; null falls back to FriendlyApiErrorMessage. */
export function mapParkingActionError(error: ParkioApiError): string | null {
  if (error.status === 404) {
    return i18n.t('parking:spotDetail.spotGone');
  }
  if (error.status === 409 && error.code === 'ACTIVE_PARKING_SESSION_EXISTS') {
    return i18n.t('parking:spotDetail.claimAlreadyActive');
  }
  if (error.status === 409) {
    return error.code === 'ALREADY_VERIFIED'
      ? i18n.t('parking:spotDetail.alreadyVerified')
      : i18n.t('parking:spotDetail.actionUnavailable');
  }
  return null;
}
