import {
  forwardRef,
  useCallback,
  useImperativeHandle,
  useMemo,
  useRef,
} from 'react';
import { StyleSheet, View, type StyleProp, type ViewStyle } from 'react-native';
import { WebView, type WebViewMessageEvent } from 'react-native-webview';
import type { LatLng } from '@parkio/geo';
import { useTheme } from '@/theme/ThemeProvider';
import { buildMapHtml, type MapSpotMarker } from './mapHtml';

export interface MapSurfaceHandle {
  setSpots: (spots: MapSpotMarker[]) => void;
  setSelected: (id: string | null) => void;
  flyTo: (target: LatLng & { zoom?: number; silent?: boolean }) => void;
  jumpTo: (target: LatLng & { zoom?: number; silent?: boolean }) => void;
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
    onMapTap,
    onMoveEnd,
    onMove,
    style,
  },
  ref,
) {
  const theme = useTheme();
  const webViewRef = useRef<WebView>(null);
  const readyRef = useRef(false);
  const queueRef = useRef<string[]>([]);

  const html = useMemo(() => {
    const dark = theme.mode === 'dark';
    return buildMapHtml({
      center: initialCenter,
      zoom: initialZoom,
      mode: theme.mode,
      interactiveSpots,
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
      },
    });
    // The map keeps its own camera; only a theme flip warrants a rebuild.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [theme.mode]);

  const dispatch = useCallback((payload: Record<string, unknown>) => {
    const json = JSON.stringify(payload);
    if (!readyRef.current) {
      queueRef.current.push(json);
      return;
    }
    webViewRef.current?.injectJavaScript(
      `window.__parkio_dispatch(${JSON.stringify(json)}); true;`,
    );
  }, []);

  useImperativeHandle(
    ref,
    () => ({
      setSpots: (spots) => dispatch({ op: 'setSpots', spots }),
      setSelected: (id) => dispatch({ op: 'setSelected', id }),
      flyTo: ({ lat, lng, zoom, silent }) => dispatch({ op: 'flyTo', lat, lng, zoom, silent }),
      jumpTo: ({ lat, lng, zoom, silent }) => dispatch({ op: 'jumpTo', lat, lng, zoom, silent }),
      setUserLocation: (location) => dispatch({ op: 'setUserLocation', location }),
    }),
    [dispatch],
  );

  const handleMessage = useCallback(
    (event: WebViewMessageEvent) => {
      let message: { type?: string; [key: string]: unknown };
      try {
        message = JSON.parse(event.nativeEvent.data) as { type?: string };
      } catch {
        return;
      }
      switch (message.type) {
        case 'ready': {
          readyRef.current = true;
          const queued = queueRef.current;
          queueRef.current = [];
          for (const json of queued) {
            webViewRef.current?.injectJavaScript(
              `window.__parkio_dispatch(${JSON.stringify(json)}); true;`,
            );
          }
          onReady?.();
          break;
        }
        case 'spotTap':
          if (typeof message.id === 'string') {
            onSpotTap?.(message.id);
          }
          break;
        case 'mapTap':
          onMapTap?.();
          break;
        case 'moveEnd':
          if (typeof message.lat === 'number' && typeof message.lng === 'number') {
            onMoveEnd?.({
              lat: message.lat,
              lng: message.lng,
              zoom: typeof message.zoom === 'number' ? message.zoom : initialZoom,
              byGesture: Boolean(message.byGesture),
            });
          }
          break;
        case 'move':
          if (typeof message.lat === 'number' && typeof message.lng === 'number') {
            onMove?.({ lat: message.lat, lng: message.lng });
          }
          break;
        case 'error':
          console.warn('[map] webview error:', message.code);
          break;
        case 'debug':
          console.log('[map]', message.message);
          break;
        default:
          break;
      }
    },
    [initialZoom, onMapTap, onMove, onMoveEnd, onReady, onSpotTap],
  );

  return (
    <View style={[styles.container, style]}>
      <WebView
        ref={webViewRef}
        source={{ html }}
        onMessage={handleMessage}
        originWhitelist={['*']}
        javaScriptEnabled
        domStorageEnabled
        allowsBackForwardNavigationGestures={false}
        setSupportMultipleWindows={false}
        overScrollMode="never"
        bounces={false}
        style={styles.webview}
        containerStyle={styles.webview}
        androidLayerType="hardware"
      />
    </View>
  );
});

const styles = StyleSheet.create({
  container: { flex: 1, overflow: 'hidden' },
  webview: { flex: 1, backgroundColor: 'transparent' },
});
