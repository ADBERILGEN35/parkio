import type { MunicipalFacility } from '@parkio/types';
import React from 'react';
import { fireEvent } from '@testing-library/react-native';
import { renderWithProviders } from '@/test/renderWithProviders';
import { MunicipalFacilitySheet } from '../MunicipalFacilitySheet';

jest.mock('@/providers/ToastProvider', () => ({
  useToast: () => ({ show: jest.fn() }),
}));

jest.mock('expo-linking', () => ({
  canOpenURL: jest.fn(async () => true),
  openURL: jest.fn(async () => undefined),
}));

jest.mock('@gorhom/bottom-sheet', () => {
  const ReactNative = require('react-native');
  const ReactLocal = require('react');
  return {
    __esModule: true,
    default: ReactLocal.forwardRef(({ children }: { children: React.ReactNode }, _ref: unknown) => (
      <ReactNative.View testID="municipal-sheet">{children}</ReactNative.View>
    )),
    BottomSheetScrollView: ({ children }: { children: React.ReactNode }) => (
      <ReactNative.ScrollView>{children}</ReactNative.ScrollView>
    ),
  };
});

function makeFacility(overrides: Partial<MunicipalFacility> = {}): MunicipalFacility {
  return {
    id: '70db58f2-4cca-4010-9315-fa46b30fba1e',
    displayName: 'Konak Otopark',
    operatorName: null,
    facilityType: 'OFF_STREET',
    addressText: 'Konak, İzmir',
    latitude: 38.4237,
    longitude: 27.1428,
    capacityTotal: 120,
    availableSpaces: null,
    occupiedSpaces: null,
    freshness: 'UNAVAILABLE',
    attribution: 'OpenStreetMap contributors',
    sourceLabel: 'OpenStreetMap',
    lastUpdatedAt: '2026-08-05T12:00:00Z',
    contributingSourceKeys: ['osm-geofabrik-turkey'],
    selectedFieldProvenanceSummary: {
      ATTRIBUTION: 'osm-geofabrik-turkey',
      COORDINATES: 'osm-geofabrik-turkey',
    },
    registryConfidenceOrReviewStatus: null,
    availabilitySource: null,
    availabilityFreshness: 'UNAVAILABLE',
    availabilityObservationTimestamp: null,
    ...overrides,
  };
}

describe('MunicipalFacilitySheet', () => {
  it('renders live İZUM available capacity without raw source keys', () => {
    const { getByText, queryByText } = renderWithProviders(
      <MunicipalFacilitySheet
        facility={makeFacility({
          availableSpaces: 4,
          occupiedSpaces: 16,
          capacityTotal: 20,
          freshness: 'LIVE',
          availabilityFreshness: 'LIVE',
          contributingSourceKeys: ['izmir-izum-otoparklar'],
          sourceLabel: 'IZUM',
        })}
        distanceMeters={250}
        onClose={jest.fn()}
      />,
    );

    expect(getByText('Konak Otopark')).toBeTruthy();
    expect(getByText(/İzmir Büyükşehir Belediyesi \/ İZUM/)).toBeTruthy();
    expect(getByText('4')).toBeTruthy();
    expect(queryByText(/osm-geofabrik/)).toBeNull();
    expect(queryByText(/izmir-izum/)).toBeNull();
    expect(queryByText('38.4237')).toBeNull();
    expect(queryByText('Detay')).toBeNull();
  });

  it('shows stale İZUM copy without numeric occupancy as current', () => {
    const { getByText } = renderWithProviders(
      <MunicipalFacilitySheet
        facility={makeFacility({
          availableSpaces: null,
          capacityTotal: 59,
          freshness: 'STALE',
          availabilityFreshness: 'STALE',
          contributingSourceKeys: ['izmir-izum-otoparklar'],
        })}
        distanceMeters={null}
        onClose={jest.fn()}
      />,
    );

    expect(getByText('Canlı veri geçici olarak güncel değil')).toBeTruthy();
  });

  it('shows OSM static copy', () => {
    const { getByText } = renderWithProviders(
      <MunicipalFacilitySheet
        facility={makeFacility()}
        distanceMeters={null}
        onClose={jest.fn()}
      />,
    );

    expect(getByText('Canlı doluluk paylaşılmıyor')).toBeTruthy();
    expect(getByText('OpenStreetMap')).toBeTruthy();
    expect(getByText('Konak, İzmir')).toBeTruthy();
  });

  it('exposes Open in Maps and optional detail CTA only when provided', () => {
    const onOpenDetail = jest.fn();
    const { getByText, queryByText, rerender } = renderWithProviders(
      <MunicipalFacilitySheet
        facility={makeFacility()}
        distanceMeters={null}
        onClose={jest.fn()}
      />,
    );
    expect(getByText('Haritada aç')).toBeTruthy();
    expect(queryByText('Detay')).toBeNull();

    rerender(
      <MunicipalFacilitySheet
        facility={makeFacility()}
        distanceMeters={null}
        onClose={jest.fn()}
        onOpenDetail={onOpenDetail}
      />,
    );
    fireEvent.press(getByText('Detay'));
    expect(onOpenDetail).toHaveBeenCalledWith('70db58f2-4cca-4010-9315-fa46b30fba1e');
  });
});
