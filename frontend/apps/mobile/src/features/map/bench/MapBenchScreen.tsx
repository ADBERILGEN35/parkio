import { useCallback, useMemo, useRef, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { useLocalSearchParams } from 'expo-router';
import { DEFAULT_MAP_CENTER, DEFAULT_MAP_ZOOM } from '@parkio/geo';
import type { SpotWithDistance } from '@parkio/geo';
import { AppText } from '@/components/ui';
import { useTheme } from '@/theme';
import { SmartReturnBanner } from '../components/SmartReturnBanner';
import { SpotSheet } from '../components/SpotSheet';
import { generateBenchSpots } from '../webmap/benchSpots';
import { MapSurface } from '../webmap/MapSurface';
import type { MapBenchResult, MapSurfaceHandle, MapTelemetry } from '../webmap/types';

const SPOT_COUNTS = [0, 10, 100, 500, 1000] as const;

/**
 * Map performance benchmark harness (DEV ONLY). Mounts the real {@link MapSurface}
 * with a controlled number of SYNTHETIC spots and the toggles that matter for
 * jank — clustering, the Smart Return banner, the bottom sheet — then runs a
 * scripted pan inside the WebView and reports real frame-time stats plus live
 * per-second bridge/render telemetry. Nothing here ships in a user flow; it
 * exists so the M2.5 before/after numbers are measured on-device, not guessed.
 */
/** Parse a deep-link param that may arrive as string | string[] | undefined. */
function param(v: string | string[] | undefined): string | undefined {
  return Array.isArray(v) ? v[0] : v;
}
function flag(v: string | string[] | undefined, fallback: boolean): boolean {
  const s = param(v);
  if (s == null) return fallback;
  return s === '1' || s === 'true' || s === 'on';
}

export function MapBenchScreen() {
  const theme = useTheme();
  const mapRef = useRef<MapSurfaceHandle>(null);

  // Optional deep-link driven config so the M2.5 matrix can be run
  // deterministically (one labelled cell per launch) rather than by blind taps,
  // e.g. exp://HOST/--/devbench?spots=1000&cluster=0&banner=1&sheet=1&run=1
  const params = useLocalSearchParams();
  const paramSpots = param(params.spots);
  const initialSpots =
    paramSpots != null && SPOT_COUNTS.includes(Number(paramSpots) as (typeof SPOT_COUNTS)[number])
      ? Number(paramSpots)
      : 100;
  const autoRun = flag(params.run, false);

  const [spotCount, setSpotCount] = useState<number>(initialSpots);
  const [cluster, setCluster] = useState(flag(params.cluster, true));
  const [showBanner, setShowBanner] = useState(flag(params.banner, false));
  const [showSheet, setShowSheet] = useState(flag(params.sheet, false));
  const [running, setRunning] = useState(false);
  const [telemetry, setTelemetry] = useState<MapTelemetry | null>(null);
  const [result, setResult] = useState<MapBenchResult | null>(null);

  const spots = useMemo(
    () => generateBenchSpots({ center: DEFAULT_MAP_CENTER, count: spotCount }),
    [spotCount],
  );

  // A single synthetic spot to populate the sheet when "Sheet" is on.
  const sheetSpot = useMemo<SpotWithDistance | null>(
    () => (showSheet && spots[0] ? { ...spots[0], distanceMeters: 120 } : null),
    [showSheet, spots],
  );

  const configLabel = `${spotCount} spots · cluster ${cluster ? 'on' : 'off'} · banner ${
    showBanner ? 'on' : 'off'
  } · sheet ${showSheet ? 'on' : 'off'}`;

  const onBench = useCallback((r: MapBenchResult) => {
    setResult(r);
    setRunning(false);
  }, []);

  const runBenchmark = useCallback(() => {
    setRunning(true);
    setResult(null);
    mapRef.current?.runBenchmark({ durationMs: 4000, thresholdMs: 16.7 });
  }, []);

  const onReady = useCallback(() => {
    mapRef.current?.setTelemetry(true);
    // When launched with ?run=1, kick the pan benchmark automatically once the
    // map is up so a single deep link produces one deterministic matrix cell.
    if (autoRun) {
      setTimeout(runBenchmark, 1200);
    }
  }, [autoRun, runBenchmark]);

  return (
    <View style={styles.fill}>
      <MapSurface
        ref={mapRef}
        initialCenter={DEFAULT_MAP_CENTER}
        initialZoom={DEFAULT_MAP_ZOOM}
        spots={spots}
        selectedSpotId={null}
        userLocation={null}
        clusterSpots={cluster}
        onReady={onReady}
        onTelemetry={setTelemetry}
        onBench={onBench}
      />

      <SmartReturnBanner visible={showBanner} topOffset={16} onDismiss={() => setShowBanner(false)} />

      <ScrollView
        style={[styles.panel, { backgroundColor: theme.colors.surface, borderColor: theme.colors.border }]}
        contentContainerStyle={styles.panelContent}
      >
        <AppText variant="label" tone="muted">
          SPOTS (synthetic)
        </AppText>
        <Row>
          {SPOT_COUNTS.map((n) => (
            <Chip key={n} label={String(n)} active={spotCount === n} onPress={() => setSpotCount(n)} />
          ))}
        </Row>

        <Row>
          <Chip label={`Cluster: ${cluster ? 'on' : 'off'}`} active={cluster} onPress={() => setCluster((v) => !v)} />
          <Chip label={`Banner: ${showBanner ? 'on' : 'off'}`} active={showBanner} onPress={() => setShowBanner((v) => !v)} />
          <Chip label={`Sheet: ${showSheet ? 'on' : 'off'}`} active={showSheet} onPress={() => setShowSheet((v) => !v)} />
        </Row>

        <Pressable
          accessibilityRole="button"
          onPress={runBenchmark}
          disabled={running}
          style={[styles.runBtn, { backgroundColor: running ? theme.colors.textMuted : theme.colors.primary }]}
        >
          <AppText variant="label" style={{ color: theme.colors.onPrimary }}>
            {running ? 'Running pan…' : 'Run pan benchmark (4s)'}
          </AppText>
        </Pressable>

        {telemetry ? (
          <View style={styles.metrics}>
            <AppText variant="label" tone="muted">
              LIVE / sec
            </AppText>
            <AppText variant="caption">
              render {telemetry.render} · out {telemetry.outbound} · inject {telemetry.injected} · region{' '}
              {telemetry.region} · err {telemetry.error}
            </AppText>
          </View>
        ) : null}

        {result ? (
          <View style={styles.metrics}>
            <AppText variant="label" tone="muted">
              FRAME TIME (ms){result.error ? ` — ${result.error}` : ''}
            </AppText>
            <AppText variant="caption">{configLabel}</AppText>
            <AppText variant="caption">
              p50 {result.p50} · p90 {result.p90} · p95 {result.p95} · p99 {result.p99}
            </AppText>
            <AppText variant="caption">
              jank {result.jank}/{result.frames} ({result.jankPct}%) · avg {result.avg} · max {result.max}
            </AppText>
          </View>
        ) : null}
      </ScrollView>

      <SpotSheet spot={sheetSpot} onClose={() => setShowSheet(false)} />
    </View>
  );
}

function Row({ children }: { children: React.ReactNode }) {
  return <View style={styles.row}>{children}</View>;
}

function Chip({ label, active, onPress }: { label: string; active: boolean; onPress: () => void }) {
  const theme = useTheme();
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ selected: active }}
      onPress={onPress}
      style={[
        styles.chip,
        {
          backgroundColor: active ? theme.colors.primary : theme.colors.background,
          borderColor: active ? theme.colors.primary : theme.colors.border,
        },
      ]}
    >
      <AppText variant="caption" style={{ color: active ? theme.colors.onPrimary : theme.colors.textMuted }}>
        {label}
      </AppText>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  fill: { flex: 1 },
  panel: {
    position: 'absolute',
    top: 8,
    left: 8,
    right: 8,
    maxHeight: 260,
    borderWidth: 1,
    borderRadius: 14,
  },
  panelContent: { padding: 12, gap: 10 },
  row: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  chip: { paddingVertical: 6, paddingHorizontal: 12, borderRadius: 999, borderWidth: 1 },
  runBtn: { paddingVertical: 12, borderRadius: 10, alignItems: 'center' },
  metrics: { gap: 4 },
});
