import { StyleSheet, View } from 'react-native';
import Svg, { Circle } from 'react-native-svg';
import { AppText } from './AppText';
import { radius as radiusTokens } from '@/theme/tokens';
import { useTheme } from '@/theme/ThemeProvider';

export interface RadiusDiagramProps {
  /** Current reach label, e.g. "1200 m". */
  currentLabel: string;
  /** Next-level reach label, e.g. "Seviye 4 · 1800 m"; omit at max level. */
  nextLabel?: string | null;
  height?: number;
}

/**
 * "Level = sight" (brief §5.7): concentric reach circles over a faint abstract
 * block map. Current reach = solid primary circle; next level = larger dashed
 * circle with its chip.
 */
export function RadiusDiagram({ currentLabel, nextLabel, height = 200 }: RadiusDiagramProps) {
  const theme = useTheme();
  const { colors } = theme;
  const dark = theme.mode === 'dark';
  const blockColor = dark ? '#16273F' : '#E2E7EC';
  const parkColor = dark ? '#0F2A1F' : '#DEE8DC';

  const currentR = height * 0.28;
  const nextR = height * 0.42;

  return (
    <View
      style={[
        styles.container,
        { height, backgroundColor: dark ? '#0F1C30' : '#ECEFF3', borderRadius: radiusTokens.card },
      ]}
      accessibilityElementsHidden
    >
      {/* Faint abstract city blocks. */}
      <View style={[styles.block, { backgroundColor: blockColor, top: 16, left: 14, width: 52, height: 34 }]} />
      <View style={[styles.block, { backgroundColor: parkColor, top: 22, right: 24, width: 44, height: 44 }]} />
      <View style={[styles.block, { backgroundColor: blockColor, bottom: 20, left: 34, width: 38, height: 46 }]} />
      <View style={[styles.block, { backgroundColor: blockColor, bottom: 26, right: 40, width: 56, height: 30 }]} />
      <View style={[styles.street, { backgroundColor: dark ? '#FFFFFF12' : '#FFFFFFE6', top: height * 0.52 }]} />
      <View style={[styles.streetV, { backgroundColor: dark ? '#FFFFFF12' : '#FFFFFFE6' }]} />

      <View style={styles.center}>
        <Svg width={nextR * 2 + 8} height={nextR * 2 + 8}>
          {nextLabel ? (
            <Circle
              cx={nextR + 4}
              cy={nextR + 4}
              r={nextR}
              stroke={colors.primaryFixedDim}
              strokeWidth={1.5}
              strokeDasharray="5 6"
              fill="none"
            />
          ) : null}
          <Circle
            cx={nextR + 4}
            cy={nextR + 4}
            r={currentR}
            stroke={colors.primary}
            strokeWidth={2}
            fill={dark ? '#0066FF14' : '#0050CB0F'}
          />
          <Circle cx={nextR + 4} cy={nextR + 4} r={4} fill={colors.primary} />
        </Svg>
        {/* Current-reach chip pinned to the circle's top edge. */}
        <View
          style={[
            styles.chip,
            {
              backgroundColor: colors.primary,
              top: nextR + 4 - currentR - 12,
            },
          ]}
        >
          <AppText variant="labelSm" tabular color={colors.onPrimary}>
            {currentLabel}
          </AppText>
        </View>
        {nextLabel ? (
          <View
            style={[
              styles.chip,
              styles.nextChip,
              {
                backgroundColor: dark ? colors.surfaceContainer3 : '#FFFFFF',
                top: -6,
              },
            ]}
          >
            <AppText variant="labelSm" tabular color={dark ? colors.primaryFixedDim : colors.primary}>
              {nextLabel}
            </AppText>
          </View>
        ) : null}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { overflow: 'hidden', alignItems: 'center', justifyContent: 'center' },
  block: { position: 'absolute', borderRadius: 6 },
  street: { position: 'absolute', left: 0, right: 0, height: 8 },
  streetV: { position: 'absolute', top: 0, bottom: 0, width: 8, left: '30%' },
  center: { alignItems: 'center', justifyContent: 'center' },
  chip: {
    position: 'absolute',
    alignSelf: 'center',
    paddingHorizontal: 8,
    paddingVertical: 3,
    borderRadius: 999,
  },
  nextChip: {},
});
