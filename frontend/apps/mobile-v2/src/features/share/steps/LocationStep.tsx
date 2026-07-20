import { useEffect, useRef, useState } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { DEFAULT_MAP_CENTER, DEFAULT_PICKER_ZOOM, LOCATED_ZOOM } from '@parkio/geo';
import type { GeocodeResult } from '@parkio/types';
import { AppText } from '@/components/ui/AppText';
import { Button } from '@/components/ui/Button';
import { Card } from '@/components/ui/Card';
import { Chip } from '@/components/ui/Chip';
import { TextField } from '@/components/ui/TextField';
import { MapSurface, type MapSurfaceHandle } from '@/features/map/MapSurface';
import { useLocation, usePlaceSearch } from '@/features/map/hooks';
import { useT } from '@/i18n/LocaleProvider';
import { useTheme } from '@/theme/ThemeProvider';
import { useShareDraftStore } from '../state/shareDraftStore';

/** GPS accuracy worse than this asks the user to wait / adjust by hand. */
const LOW_ACCURACY_M = 35;

/**
 * Step 2 — pinpoint + address (brief §12.5.5–6, and the flow gap this app
 * closes): GPS-accuracy gate, center-pin map adjust, free-text address, and a
 * place search whose picks move the pin.
 */
export function LocationStep() {
  const theme = useTheme();
  const t = useT();
  const { colors } = theme;
  const mapRef = useRef<MapSurfaceHandle>(null);
  const device = useLocation();

  const location = useShareDraftStore((s) => s.location);
  const manualEdited = useShareDraftStore((s) => s.manualLocationEdited);
  const addressText = useShareDraftStore((s) => s.addressText);
  const gpsAccuracy = useShareDraftStore((s) => s.gpsAccuracy);
  const setLocation = useShareDraftStore((s) => s.setLocation);
  const setAddressText = useShareDraftStore((s) => s.setAddressText);
  const setGpsAccuracy = useShareDraftStore((s) => s.setGpsAccuracy);

  const [searchQuery, setSearchQuery] = useState('');
  const places = usePlaceSearch(searchQuery);
  const seededRef = useRef(false);

  // Seed from device GPS when the draft has no location yet.
  useEffect(() => {
    if (seededRef.current) {
      return;
    }
    if (location) {
      seededRef.current = true;
      mapRef.current?.jumpTo({ lat: location.latitude, lng: location.longitude, zoom: LOCATED_ZOOM, silent: true });
      return;
    }
    void (async () => {
      const position = device.status === 'granted' ? await device.refresh() : await device.request();
      if (seededRef.current) {
        return;
      }
      if (position) {
        seededRef.current = true;
        setLocation({ latitude: position.lat, longitude: position.lng });
        setGpsAccuracy(device.accuracy);
        mapRef.current?.flyTo({ ...position, zoom: LOCATED_ZOOM, silent: true });
      }
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [device.status]);

  useEffect(() => {
    setGpsAccuracy(device.accuracy);
  }, [device.accuracy, setGpsAccuracy]);

  const goToMyLocation = async () => {
    const position = device.status === 'granted' ? await device.refresh() : await device.request();
    if (position) {
      setLocation({ latitude: position.lat, longitude: position.lng });
      mapRef.current?.flyTo({ ...position, zoom: LOCATED_ZOOM, silent: true });
    }
  };

  const pickPlace = (place: GeocodeResult) => {
    setSearchQuery('');
    setAddressText(place.secondary ? `${place.primary}, ${place.secondary}` : place.primary);
    setLocation({ latitude: place.lat, longitude: place.lng }, { manual: true });
    mapRef.current?.flyTo({ lat: place.lat, lng: place.lng, zoom: LOCATED_ZOOM, silent: true });
  };

  const accuracyState = (() => {
    if (!location) {
      return { tone: colors.onSurfaceVariant, icon: 'crosshairs-question' as const, label: t('share.gps.searching') };
    }
    if (gpsAccuracy !== null && gpsAccuracy > LOW_ACCURACY_M && !manualEdited) {
      return {
        tone: colors.tertiary,
        icon: 'crosshairs-gps' as const,
        label: t('share.gps.low', { m: Math.round(gpsAccuracy) }),
        hint: t('share.gps.lowHint'),
      };
    }
    if (manualEdited) {
      return { tone: colors.primary, icon: 'gesture-tap' as const, label: t('share.location.manualEdited') };
    }
    return {
      tone: colors.secondary,
      icon: 'crosshairs-gps' as const,
      label: t('share.gps.good', { m: Math.round(gpsAccuracy ?? 10) }),
    };
  })();

  const mapCenter = location
    ? { lat: location.latitude, lng: location.longitude }
    : (device.position ?? DEFAULT_MAP_CENTER);

  return (
    <View style={styles.container}>
      <AppText variant="titleMd">{t('share.location.title')}</AppText>

      {/* GPS gate */}
      <Card tone={1} padding={12}>
        <View style={styles.gpsRow}>
          <MaterialCommunityIcons name={accuracyState.icon} size={20} color={accuracyState.tone} />
          <View style={styles.gpsLabels}>
            <AppText variant="bodySm" color={accuracyState.tone}>
              {accuracyState.label}
            </AppText>
            {'hint' in accuracyState && accuracyState.hint ? (
              <AppText variant="labelSm" color={colors.onSurfaceVariant}>
                {accuracyState.hint}
              </AppText>
            ) : null}
          </View>
        </View>
      </Card>

      {/* Center-pin map */}
      <View style={styles.mapWrap}>
        <MapSurface
          ref={mapRef}
          initialCenter={mapCenter}
          initialZoom={location ? LOCATED_ZOOM : DEFAULT_PICKER_ZOOM}
          interactiveSpots={false}
          onMoveEnd={(event) => {
            if (event.byGesture) {
              setLocation({ latitude: event.lat, longitude: event.lng }, { manual: true });
            }
          }}
          style={styles.map}
        />
        {/* Fixed center teardrop pin (bottom tip = the exact point). */}
        <View pointerEvents="none" style={styles.pinWrap}>
          <MaterialCommunityIcons name="map-marker" size={40} color={colors.primary} style={styles.pin} />
          <View style={[styles.pinDot, { backgroundColor: colors.primary }]} />
        </View>
        <View style={styles.mapActions} pointerEvents="box-none">
          <Chip
            icon="crosshairs-gps"
            label={t('share.location.useMyLocation')}
            onPress={() => void goToMyLocation()}
          />
        </View>
      </View>
      <AppText variant="labelSm" color={colors.onSurfaceVariant}>
        {t('share.location.adjustHint')}
      </AppText>

      {/* Address entry + search-driven suggestions */}
      <TextField
        label={t('share.location.address')}
        placeholder={t('share.location.addressPlaceholder')}
        helper={t('share.location.addressHelper')}
        value={addressText}
        onChangeText={(value) => {
          setAddressText(value);
          setSearchQuery(value);
        }}
        leadingIcon="map-search-outline"
        maxLength={512}
      />
      {places.data && places.data.length > 0 && searchQuery.trim().length >= 3 ? (
        <Card tone={1} padding={6}>
          {places.data.slice(0, 5).map((place) => (
            <Pressable
              key={place.id}
              onPress={() => pickPlace(place)}
              accessibilityRole="button"
              accessibilityLabel={place.primary}
              style={({ pressed }) => [styles.placeRow, pressed && { opacity: 0.6 }]}
            >
              <MaterialCommunityIcons name="map-marker-outline" size={17} color={colors.onSurfaceVariant} />
              <View style={styles.placeLabels}>
                <AppText variant="bodySm" numberOfLines={1}>
                  {place.primary}
                </AppText>
                {place.secondary ? (
                  <AppText variant="labelSm" color={colors.onSurfaceVariant} numberOfLines={1}>
                    {place.secondary}
                  </AppText>
                ) : null}
              </View>
              <MaterialCommunityIcons name="arrow-top-left" size={15} color={colors.outline} />
            </Pressable>
          ))}
        </Card>
      ) : null}

      {device.status === 'denied' && !location ? (
        <Button label={t('map.permission.openSettings')} variant="tonal" size="md" onPress={() => void device.request()} />
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  container: { gap: 10 },
  gpsRow: { flexDirection: 'row', alignItems: 'center', gap: 10 },
  gpsLabels: { flex: 1, gap: 1 },
  mapWrap: { height: 260, borderRadius: 20, overflow: 'hidden' },
  map: { flex: 1 },
  pinWrap: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    alignItems: 'center',
    justifyContent: 'center',
  },
  pin: { marginBottom: 32 },
  pinDot: { width: 5, height: 5, borderRadius: 2.5, marginTop: -34, opacity: 0.5 },
  mapActions: { position: 'absolute', bottom: 10, left: 10 },
  placeRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    paddingHorizontal: 8,
    paddingVertical: 8,
  },
  placeLabels: { flex: 1 },
});
