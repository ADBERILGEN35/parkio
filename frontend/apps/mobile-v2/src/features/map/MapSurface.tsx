import {
  forwardRef,
  useCallback,
  useImperativeHandle,
  useMemo,
  useRef,
  useState,
} from 'react';
import { Platform, Pressable, StyleSheet, View, type StyleProp, type ViewStyle } from 'react-native';
import { WebView, type WebViewMessageEvent } from 'react-native-webview';
import type { LatLng } from '@parkio/geo';
import { AppText } from '@/components/ui/AppText';
import { useT } from '@/i18n/LocaleProvider';
import { useTheme } from '@/theme/ThemeProvider';
import { decodeMapBridgeMessage } from './mapBridgeDecode';
import {
  buildMapHtml,
  type MapMunicipalMarkerPayload,
  type MapSpotMarker,
} from './mapHtml';
import {
  shouldAllowMapWebViewNavigation,
  shouldAllowMapWebViewOpenWindow,
} from './mapWebViewNavigation';
import { buildParkioDispatchScript } from './safeJsonForHtmlScript';

export interface MapSurfaceHandle {
  setSpots: (spots: MapSpotMarker[]) => void;
  setSelected: (id: string | null) => void;
  setMunicipalFacilities: (facilities: MapMunicipalMarkerPayload[]) => void;
  setSelectedMunicipal: (id: string | null) => void;
  /** Destination pin for Smart Parking Assistant — distinct from parking markers. */
  setDestinationMarker: (
    marker: { lat: number; lng: number; label: string } | null,
  ) => void;
  /** Active ParkingSession pin — distinct from destination / municipal / community. */
  setParkedCarMarker: (
    marker: { lat: number; lng: number; label: string } | null,
  ) => void;
  /** Highlight recommended facility/spot ids already on the map (no duplicates). */
  setRecommendedHighlights: (
    payload: {
      communityIds: string[];
      municipalIds: string[];
      topCommunityId?: string | null;
      topMunicipalId?: string | null;
    } | null,
  ) => void;
  flyTo: (target: LatLng & { zoom?: number; silent?: boolean }) => void;
  jumpTo: (target: LatLng & { zoom?: number; silent?: boolean }) => void;
  /** Fit camera to a geographic bounding box (public Explore framing). */
  fitBounds: (bounds: {
    west: number;
    south: number;
    east: number;
    north: number;
    maxZoom?: number;
    padding?: { top: number; bottom: number; left: number; right: number };
    silent?: boolean;
  }) => void;
  setUserLocation: (location: LatLng | null) => void;
}

export interface MapMoveEvent extends LatLng {
  zoom: number;
  byGesture: boolean;
}

export interface MapSurfaceProps {
  initialCenter: LatLng;
  initialZoom: number;
  /** Interactive spot markers (main map); pickers pass false. */
  interactiveSpots?: boolean;
  onReady?: () => void;
  onSpotTap?: (id: string) => void;
  onMunicipalTap?: (id: string) => void;
  onMapTap?: () => void;
  onMoveEnd?: (event: MapMoveEvent) => void;
  /** Continuous move stream (center pin tracking). */
  onMove?: (center: LatLng) => void;
  style?: StyleProp<ViewStyle>;
}

/**
 * The WebView map renderer. Imperative bridge via ref; commands sent before
 * the map's `ready` event are queued and flushed on load. Rebuilds only when
 * the theme mode flips (marker/count state is re-pushed by the owner).
 */
export const MapSurface = forwardRef<MapSurfaceHandle, MapSurfaceProps>(function MapSurface(
  {
    initialCenter,
    initialZoom,
    interactiveSpots = true,
    onReady,
    onSpotTap,
    onMunicipalTap,
    onMapTap,
    onMoveEnd,
    onMove,
    style,
  },
  ref,
) {
  const theme = useTheme();
  const t = useT();
  const webViewRef = useRef<WebView>(null);
  const readyRef = useRef(false);
  const queueRef = useRef<string[]>([]);
  const [webViewEpoch, setWebViewEpoch] = useState(0);
  const [mapErrorCode, setMapErrorCode] = useState<string | null>(null);
  // Test-only injection. __DEV__ hard-gate: never active in release/production bundles
  // even if EXPO_PUBLIC_FORCE_MAP_INIT_FAIL is somehow present at build time.
  // Not actual GPU capability loss — see mapHtml hasWebGl2 probe for that path.
  const forceInitFailure =
    __DEV__ && process.env.EXPO_PUBLIC_FORCE_MAP_INIT_FAIL === 'true';

  const html = useMemo(() => {
    const dark = theme.mode === 'dark';
    return buildMapHtml({
      center: initialCenter,
      zoom: initialZoom,
      mode: theme.mode,
      interactiveSpots,
      forceInitFailure,
      colors: {
        fresh: dark ? '#4D8DFF' : '#0050CB',
        aging: dark ? '#FFB955' : '#A06500',
        expiring: dark ? '#FFB4AB' : '#BA1A1A',
        track: dark ? '#1D3049' : '#DCE9FF',
        pillBg: dark ? '#16273F' : '#FFFFFF',
        pillText: dark ? '#E7ECF7' : '#0B1C30',
        muted: '#727687',
        primary: dark ? '#4D8DFF' : '#0050CB',
        userDot: dark ? '#4D8DFF' : '#0050CB',
        userHalo: dark ? 'rgba(77,141,255,0.25)' : 'rgba(0,80,203,0.18)',
        municipal: theme.colors.secondary,
        municipalGlyph: dark ? '#0B1626' : '#FFFFFF',
        communityGlyph: '#FFFFFF',
      },
    });
    // Theme flip or forced-init test flag warrants a rebuild.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [theme.mode, forceInitFailure, webViewEpoch]);

  const dispatch = useCallback((payload: Record<string, unknown>) => {
    const script = buildParkioDispatchScript(payload);
    if (!readyRef.current) {
      queueRef.current.push(script);
      return;
    }
    webViewRef.current?.injectJavaScript(script);
  }, []);

  const retryMap = useCallback(() => {
    readyRef.current = false;
    queueRef.current = [];
    setMapErrorCode(null);
    setWebViewEpoch((epoch) => epoch + 1);
  }, []);

  useImperativeHandle(
    ref,
    () => ({
      setSpots: (spots) => dispatch({ op: 'setSpots', spots }),
      setSelected: (id) => dispatch({ op: 'setSelected', id }),
      setMunicipalFacilities: (facilities) =>
        dispatch({ op: 'setMunicipalFacilities', facilities }),
      setSelectedMunicipal: (id) => dispatch({ op: 'setSelectedMunicipal', id }),
      setDestinationMarker: (marker) => dispatch({ op: 'setDestinationMarker', marker }),
      setParkedCarMarker: (marker) => dispatch({ op: 'setParkedCarMarker', marker }),
      setRecommendedHighlights: (payload) =>
        dispatch({ op: 'setRecommendedHighlights', payload }),
      flyTo: ({ lat, lng, zoom, silent }) => dispatch({ op: 'flyTo', lat, lng, zoom, silent }),
      jumpTo: ({ lat, lng, zoom, silent }) => dispatch({ op: 'jumpTo', lat, lng, zoom, silent }),
      fitBounds: ({ west, south, east, north, maxZoom, padding, silent }) =>
        dispatch({
          op: 'fitBounds',
          west,
          south,
          east,
          north,
          maxZoom,
          padding,
          silent,
        }),
      setUserLocation: (location) => dispatch({ op: 'setUserLocation', location }),
    }),
    [dispatch],
  );

  const handleMessage = useCallback(
    (event: WebViewMessageEvent) => {
      try {
        const decoded = decodeMapBridgeMessage(event.nativeEvent.data);
        if (!decoded.ok) {
          return;
        }
        const message = decoded.message;
        switch (message.type) {
          case 'ready': {
            readyRef.current = true;
            setMapErrorCode(null);
            const queued = queueRef.current;
            queueRef.current = [];
            for (const script of queued) {
              webViewRef.current?.injectJavaScript(script);
            }
            onReady?.();
            break;
          }
          case 'boot':
            // Informational only — readiness is gated on MapLibre `load` → ready.
            break;
          case 'spotTap':
            onSpotTap?.(message.id);
            break;
          case 'municipalTap':
            onMunicipalTap?.(message.id);
            break;
          case 'mapTap':
            onMapTap?.();
            break;
          case 'moveEnd':
            onMoveEnd?.({
              lat: message.lat,
              lng: message.lng,
              zoom: message.zoom,
              byGesture: message.byGesture,
            });
            break;
          case 'move':
            onMove?.({ lat: message.lat, lng: message.lng });
            break;
          case 'error':
            console.warn('[map] webview error:', message.code);
            readyRef.current = false;
            setMapErrorCode(message.code);
            break;
          case 'debug':
            if (__DEV__) {
              console.log('[map]', message.message);
            }
            break;
          default:
            break;
        }
      } catch {
        // Never let untrusted WebView input crash the native handler.
      }
    },
    [onMapTap, onMove, onMoveEnd, onMunicipalTap, onReady, onSpotTap],
  );

  const onShouldStartLoadWithRequest = useCallback(
    (request: { url: string; isTopFrame?: boolean }) =>
      shouldAllowMapWebViewNavigation(request),
    [],
  );

  const errorTitle =
    mapErrorCode === 'webgl-unavailable'
      ? t('map.initError.webglTitle')
      : t('map.initError.title');
  const errorBody =
    mapErrorCode === 'webgl-unavailable'
      ? t('map.initError.webglBody')
      : t('map.initError.body');

  return (
    <View style={[styles.container, style]}>
      <WebView
        key={webViewEpoch}
        ref={webViewRef}
        source={{ html }}
        onMessage={handleMessage}
        onShouldStartLoadWithRequest={onShouldStartLoadWithRequest}
        onOpenWindow={() => {
          // Block window.open / target=_blank from becoming an external browser.
          void shouldAllowMapWebViewOpenWindow();
        }}
        // Inline HTML document only — no remote top-level origins.
        originWhitelist={['about:blank']}
        javaScriptEnabled
        // MapLibre DOM markers / localStorage not required; keep false for surface area.
        domStorageEnabled={false}
        allowsBackForwardNavigationGestures={false}
        setSupportMultipleWindows={false}
        javaScriptCanOpenWindowsAutomatically={false}
        allowFileAccess={false}
        allowFileAccessFromFileURLs={false}
        allowUniversalAccessFromFileURLs={false}
        mixedContentMode="never"
        overScrollMode="never"
        bounces={false}
        style={styles.webview}
        containerStyle={styles.webview}
        androidLayerType="hardware"
        {...(Platform.OS === 'android'
          ? {
              // Android: deny geolocation prompts from the map document.
              geolocationEnabled: false,
            }
          : {})}
      />
      {mapErrorCode ? (
        <View
          style={[styles.errorOverlay, { backgroundColor: theme.colors.background }]}
          accessibilityRole="alert"
          testID="map-init-error"
        >
          <AppText variant="titleMd" style={styles.errorTitle}>
            {errorTitle}
          </AppText>
          <AppText variant="bodySm" color={theme.colors.onSurfaceVariant} style={styles.errorBody}>
            {errorBody}
          </AppText>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel={t('map.initError.retry')}
            testID="map-init-retry"
            onPress={retryMap}
            style={[styles.retryButton, { backgroundColor: theme.colors.primary }]}
          >
            <AppText variant="labelMd" color={theme.colors.onPrimary}>
              {t('map.initError.retry')}
            </AppText>
          </Pressable>
        </View>
      ) : null}
    </View>
  );
});

const styles = StyleSheet.create({
  container: { flex: 1, overflow: 'hidden' },
  webview: { flex: 1, backgroundColor: 'transparent' },
  errorOverlay: {
    ...StyleSheet.absoluteFill,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 24,
    gap: 12,
  },
  errorTitle: { textAlign: 'center' },
  errorBody: { textAlign: 'center', maxWidth: 320 },
  retryButton: {
    marginTop: 8,
    paddingHorizontal: 20,
    paddingVertical: 12,
    borderRadius: 12,
  },
});
