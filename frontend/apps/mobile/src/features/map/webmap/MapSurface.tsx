import { forwardRef, memo, useCallback, useEffect, useImperativeHandle, useMemo, useRef } from 'react';
import { StyleSheet, View } from 'react-native';
import { WebView, type WebViewMessageEvent } from 'react-native-webview';
import type { LatLng } from '@parkio/geo';
import type { PublicSpot } from '@parkio/types';
import { useTheme } from '@/theme';
import { computeFrameStats } from './benchStats';
import { buildMapHtml, type MapHtmlColors } from './mapHtml';
import { spotsToGeoJson } from './spotsGeoJson';
import type {
  MapBenchResult,
  MapBounds,
  MapOutboundMessage,
  MapRegion,
  MapSurfaceHandle,
  MapTelemetry,
} from './types';

export interface MapSurfaceProps {
  initialCenter: LatLng;
  initialZoom: number;
  spots: PublicSpot[];
  selectedSpotId: string | null;
  userLocation: LatLng | null;
  onReady?: () => void;
  onError?: (reason: string) => void;
  onRegionChange?: (region: MapRegion) => void;
  onSpotPress?: (spotId: string) => void;
  onMapPress?: () => void;
  /** Dev/bench: per-second bridge + render counters (only fires once enabled). */
  onTelemetry?: (telemetry: MapTelemetry) => void;
  /** Dev/bench: scripted-pan frame-time result. */
  onBench?: (result: MapBenchResult) => void;
  /** Cluster markers (default true). Changing this reloads the renderer. */
  clusterSpots?: boolean;
}

/**
 * Isolated map renderer: a MapLibre GL JS map hosted in a WebView. All RN→map
 * calls go through the imperative {@link MapSurfaceHandle}; all map→RN events
 * arrive as typed {@link MapOutboundMessage}s. Nothing else in the app knows the
 * renderer is a WebView, so it can be replaced behind this same surface.
 *
 * Spots/user-location are pushed via `injectJavaScript` (not React re-renders),
 * so marker updates never reload the WebView or drop a frame.
 */
function MapSurfaceImpl(
  props: MapSurfaceProps,
  ref: React.Ref<MapSurfaceHandle>,
) {
  const {
    initialCenter,
    initialZoom,
    spots,
    selectedSpotId,
    userLocation,
    onReady,
    onError,
    onRegionChange,
    onSpotPress,
    onMapPress,
    onTelemetry,
    onBench,
    clusterSpots = true,
  } = props;
  const theme = useTheme();
  const webRef = useRef<WebView>(null);
  const readyRef = useRef(false);
  const disposedRef = useRef(false);
  const pendingCommandsRef = useRef<string[]>([]);
  // Last payloads actually sent to the renderer. Re-pushing identical spots or
  // user-location forces MapLibre to rebuild its cluster index + repaint, which
  // is exactly the kind of redundant work that drops frames during interaction,
  // so we skip the injection when the serialized payload hasn't changed.
  const lastSpotsRef = useRef<string | null>(null);
  const lastUserRef = useRef<string | null>(null);

  const colors = useMemo<MapHtmlColors>(
    () => ({
      success: theme.colors.success,
      warning: theme.colors.warning,
      danger: theme.colors.danger,
      muted: theme.colors.textMuted,
      primary: theme.colors.primary,
      onPrimary: theme.colors.onPrimary,
      clusterFill: theme.colors.primary,
      clusterText: theme.colors.onPrimary,
      userDot: theme.colors.primary,
      userHalo: theme.colors.primary,
    }),
    [theme],
  );

  // The HTML is built once per theme scheme; spots/location flow in imperatively.
  const html = useMemo(
    () =>
      buildMapHtml({
        center: initialCenter,
        zoom: initialZoom,
        colorScheme: theme.scheme,
        colors,
        cluster: clusterSpots,
      }),
    // initialCenter/zoom intentionally frozen at mount; camera moves use the handle.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [theme.scheme, colors, clusterSpots],
  );

  const injectNow = useCallback((js: string) => {
    if (disposedRef.current) return;
    webRef.current?.injectJavaScript(`${js}; true;`);
  }, []);

  const enqueue = useCallback(
    (js: string) => {
      if (disposedRef.current) return;
      if (!readyRef.current) {
        pendingCommandsRef.current.push(js);
        return;
      }
      injectNow(js);
    },
    [injectNow],
  );

  const flushPendingCommands = useCallback(() => {
    const pending = pendingCommandsRef.current;
    pendingCommandsRef.current = [];
    for (const js of pending) {
      injectNow(js);
    }
  }, [injectNow]);

  const pushSpots = useCallback(() => {
    const fc = JSON.stringify(spotsToGeoJson(spots, selectedSpotId));
    if (fc === lastSpotsRef.current) return; // unchanged → no re-cluster/repaint
    lastSpotsRef.current = fc;
    enqueue(`window.__parkio && window.__parkio.setSpots(${fc})`);
  }, [enqueue, spots, selectedSpotId]);

  const pushUser = useCallback(() => {
    const loc = userLocation ? JSON.stringify(userLocation) : 'null';
    if (loc === lastUserRef.current) return; // unchanged → skip
    lastUserRef.current = loc;
    enqueue(`window.__parkio && window.__parkio.setUserLocation(${loc})`);
  }, [enqueue, userLocation]);

  useEffect(() => {
    disposedRef.current = false;
    const webView = webRef.current;
    return () => {
      disposedRef.current = true;
      readyRef.current = false;
      pendingCommandsRef.current = [];
      lastSpotsRef.current = null;
      lastUserRef.current = null;
      webView?.injectJavaScript('window.__parkio && window.__parkio.dispose && window.__parkio.dispose(); true;');
    };
  }, []);

  // Re-push whenever inputs change AND the map is ready.
  useEffect(() => {
    if (readyRef.current) pushSpots();
  }, [pushSpots]);
  useEffect(() => {
    if (readyRef.current) pushUser();
  }, [pushUser]);

  useImperativeHandle(
    ref,
    (): MapSurfaceHandle => ({
      setCamera: (center: LatLng, zoom?: number, animate = true) => {
        const z = typeof zoom === 'number' ? zoom : 'undefined';
        enqueue(
          `window.__parkio && window.__parkio.setCamera(${JSON.stringify(center)}, ${z}, ${animate})`,
        );
      },
      fitBounds: (bounds: MapBounds, padding?: number) => {
        enqueue(
          `window.__parkio && window.__parkio.fitBounds(${JSON.stringify(bounds)}, ${padding ?? 64})`,
        );
      },
      setTelemetry: (enabled: boolean) => {
        enqueue(`window.__parkio && window.__parkio.setTelemetry(${enabled ? 'true' : 'false'})`);
      },
      runBenchmark: (opts?: { durationMs?: number; thresholdMs?: number }) => {
        enqueue(`window.__parkioBench && window.__parkioBench.run(${JSON.stringify(opts ?? {})})`);
      },
    }),
    [enqueue],
  );

  const handleMessage = (event: WebViewMessageEvent) => {
    if (disposedRef.current) return;
    let msg: MapOutboundMessage;
    try {
      msg = JSON.parse(event.nativeEvent.data) as MapOutboundMessage;
    } catch {
      return;
    }
    switch (msg.type) {
      case 'ready':
        readyRef.current = true;
        // Fresh (or reloaded) renderer starts empty — clear the dedupe memory so
        // the initial spots/user push always lands.
        lastSpotsRef.current = null;
        lastUserRef.current = null;
        pushSpots();
        pushUser();
        flushPendingCommands();
        onReady?.();
        break;
      case 'error':
        onError?.(msg.reason);
        break;
      case 'telemetry':
        onTelemetry?.({
          outbound: msg.outbound,
          injected: msg.injected,
          region: msg.region,
          render: msg.render,
          error: msg.error,
        });
        break;
      case 'bench':
        if (msg.error) {
          onBench?.({ ...computeFrameStats([], msg.thresholdMs ?? 16.7), error: msg.error });
        } else {
          onBench?.(computeFrameStats(msg.frames ?? [], msg.thresholdMs ?? 16.7));
        }
        break;
      case 'region':
        onRegionChange?.({ center: msg.center, zoom: msg.zoom, bounds: msg.bounds });
        break;
      case 'spotPress':
        onSpotPress?.(msg.spotId);
        break;
      case 'mapPress':
        onMapPress?.();
        break;
    }
  };

  return (
    <View style={styles.fill}>
      <WebView
        ref={webRef}
        style={styles.fill}
        originWhitelist={['*']}
        source={{ html }}
        onMessage={handleMessage}
        onError={(event) => onError?.(event.nativeEvent.description || 'webview-error')}
        onHttpError={(event) => onError?.(`webview-http-${event.nativeEvent.statusCode}`)}
        onContentProcessDidTerminate={() => onError?.('webview-content-process-terminated')}
        javaScriptEnabled
        domStorageEnabled
        // The map owns gestures; let RN siblings (FABs, sheet) still receive touches.
        androidLayerType="hardware"
        // A blank/transparent canvas while tiles fetch (we render our own loader).
        startInLoadingState={false}
        allowsInlineMediaPlayback
        setSupportMultipleWindows={false}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  fill: { flex: 1, backgroundColor: 'transparent' },
});

export const MapSurface = memo(forwardRef<MapSurfaceHandle, MapSurfaceProps>(MapSurfaceImpl));
