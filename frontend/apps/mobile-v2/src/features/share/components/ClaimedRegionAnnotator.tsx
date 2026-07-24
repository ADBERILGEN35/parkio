import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { LayoutChangeEvent, PanResponder, StyleSheet, View } from 'react-native';
import type { ClaimedRegion } from '@parkio/types';
import { CLAIMED_REGION_MIN_AREA, isValidClaimedRegion } from '@parkio/types';
import { AppText } from '@/components/ui/AppText';
import { useT } from '@/i18n/LocaleProvider';
import { useTheme } from '@/theme/ThemeProvider';
import {
  computeContainContentRect,
  containerPointToNormalizedRegion,
  normalizedRegionToContainerBox,
  type ContentRect,
  type PixelBox,
} from '../lib/imageRegionCoords';

export interface ClaimedRegionAnnotatorProps {
  /** Prepared (EXIF-normalized) image pixel size — must match the upload JPEG. */
  imageWidth: number;
  imageHeight: number;
  /** Called when the user finishes a valid drag-box (or clears). */
  onChange: (region: ClaimedRegion | null) => void;
  value?: ClaimedRegion | null;
}

interface LayoutSize {
  width: number;
  height: number;
}

/**
 * Tap-drag axis-aligned box overlay for marking the claimed free parking space.
 * Coordinates are normalized to the prepared image content rect (objectFit contain),
 * excluding letterbox padding.
 */
export function ClaimedRegionAnnotator({
  imageWidth,
  imageHeight,
  onChange,
  value,
}: ClaimedRegionAnnotatorProps) {
  const theme = useTheme();
  const t = useT();
  const { colors } = theme;
  const [layout, setLayout] = useState<LayoutSize>({ width: 0, height: 0 });
  const [draftBox, setDraftBox] = useState<PixelBox | null>(null);
  const [tooSmall, setTooSmall] = useState(false);
  const startRef = useRef<{ x: number; y: number } | null>(null);
  const endRef = useRef<{ x: number; y: number } | null>(null);
  const contentRef = useRef<ContentRect | null>(null);

  const contentRect: ContentRect | null = useMemo(() => {
    if (layout.width <= 0 || layout.height <= 0 || imageWidth <= 0 || imageHeight <= 0) {
      return null;
    }
    return computeContainContentRect(layout.width, layout.height, imageWidth, imageHeight);
  }, [layout, imageWidth, imageHeight]);

  useEffect(() => {
    contentRef.current = contentRect;
  }, [contentRect]);

  const onChangeRef = useRef(onChange);
  useEffect(() => {
    onChangeRef.current = onChange;
  }, [onChange]);

  const committedBox = useMemo(() => {
    if (!value || !contentRect) {
      return null;
    }
    return normalizedRegionToContainerBox(value, contentRect);
  }, [value, contentRect]);

  const visibleBox = draftBox ?? committedBox;

  const onLayout = useCallback((event: LayoutChangeEvent) => {
    const { width, height } = event.nativeEvent.layout;
    setLayout({ width, height });
  }, []);

  const updateDraftFromPoints = (start: { x: number; y: number }, end: { x: number; y: number }) => {
    const content = contentRef.current;
    if (!content) {
      return;
    }
    const leftBound = content.offsetX;
    const topBound = content.offsetY;
    const rightBound = content.offsetX + content.contentW;
    const bottomBound = content.offsetY + content.contentH;
    const clamp = (n: number, min: number, max: number) => Math.max(min, Math.min(max, n));
    const x0 = clamp(start.x, leftBound, rightBound);
    const y0 = clamp(start.y, topBound, bottomBound);
    const x1 = clamp(end.x, leftBound, rightBound);
    const y1 = clamp(end.y, topBound, bottomBound);
    const next: PixelBox = {
      left: Math.min(x0, x1),
      top: Math.min(y0, y1),
      width: Math.abs(x1 - x0),
      height: Math.abs(y1 - y0),
    };
    setDraftBox(next);
  };

  const [panHandlers, setPanHandlers] = useState<ReturnType<
    typeof PanResponder.create
  >['panHandlers'] | null>(null);

  useEffect(() => {
    const responder = PanResponder.create({
      onStartShouldSetPanResponder: () => true,
      onMoveShouldSetPanResponder: () => true,
      onPanResponderGrant: (event) => {
        if (!contentRef.current) {
          return;
        }
        const { locationX, locationY } = event.nativeEvent;
        startRef.current = { x: locationX, y: locationY };
        endRef.current = { x: locationX, y: locationY };
        setTooSmall(false);
        updateDraftFromPoints(startRef.current, endRef.current);
      },
      onPanResponderMove: (event) => {
        const start = startRef.current;
        if (!start || !contentRef.current) {
          return;
        }
        endRef.current = { x: event.nativeEvent.locationX, y: event.nativeEvent.locationY };
        updateDraftFromPoints(start, endRef.current);
      },
      onPanResponderRelease: () => {
        const content = contentRef.current;
        const start = startRef.current;
        const end = endRef.current;
        startRef.current = null;
        endRef.current = null;
        setDraftBox(null);
        if (!content || !start || !end) {
          return;
        }
        const region = containerPointToNormalizedRegion(start, end, content);
        if (!region || !isValidClaimedRegion(region)) {
          setTooSmall(true);
          onChangeRef.current(null);
          return;
        }
        setTooSmall(false);
        onChangeRef.current(region);
      },
      onPanResponderTerminate: () => {
        startRef.current = null;
        endRef.current = null;
        setDraftBox(null);
      },
    });
    setPanHandlers(responder.panHandlers);
  }, []);

  return (
    <View style={styles.wrap} onLayout={onLayout} {...(panHandlers ?? {})}>
      <View style={[styles.hint, { backgroundColor: 'rgba(0,0,0,0.45)' }]} pointerEvents="none">
        <AppText variant="labelSm" color="#FFFFFF" style={styles.hintText}>
          {t('share.annotate.title')}
        </AppText>
        <AppText variant="labelSm" color="#FFFFFF" style={styles.hintText}>
          {t('share.annotate.hint')}
        </AppText>
      </View>
      {visibleBox && visibleBox.width > 0 && visibleBox.height > 0 ? (
        <View
          pointerEvents="none"
          style={[
            styles.box,
            {
              left: visibleBox.left,
              top: visibleBox.top,
              width: visibleBox.width,
              height: visibleBox.height,
              borderColor: colors.primary,
              backgroundColor: `${colors.primary}33`,
            },
          ]}
        />
      ) : null}
      {tooSmall ? (
        <View style={[styles.errorBanner, { backgroundColor: colors.errorContainer }]} pointerEvents="none">
          <AppText variant="labelSm" color={colors.error}>
            {t('share.annotate.tooSmall', { percent: Math.round(CLAIMED_REGION_MIN_AREA * 100) })}
          </AppText>
        </View>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: {
    position: 'absolute',
    left: 0,
    right: 0,
    top: 0,
    bottom: 0,
  },
  hint: {
    position: 'absolute',
    top: 10,
    left: 10,
    right: 10,
    borderRadius: 10,
    paddingHorizontal: 10,
    paddingVertical: 8,
    gap: 2,
  },
  hintText: { textAlign: 'center' },
  box: {
    position: 'absolute',
    borderWidth: 2,
    borderRadius: 4,
  },
  errorBanner: {
    position: 'absolute',
    bottom: 10,
    left: 10,
    right: 10,
    borderRadius: 10,
    paddingHorizontal: 10,
    paddingVertical: 8,
  },
});