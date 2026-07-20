import { useRef, useState } from 'react';
import { FlatList, StyleSheet, View, useWindowDimensions } from 'react-native';
import { useRouter } from 'expo-router';
import { SafeAreaView } from 'react-native-safe-area-context';
import { AppText } from '@/components/ui/AppText';
import { Button } from '@/components/ui/Button';
import { PulseMotif } from '@/components/ui/PulseMotif';
import { RadiusDiagram } from '@/components/ui/RadiusDiagram';
import { useT } from '@/i18n/LocaleProvider';
import type { TranslationKey } from '@/i18n/translations';
import { useTheme } from '@/theme/ThemeProvider';

interface Slide {
  key: string;
  titleKey: TranslationKey;
  bodyKey: TranslationKey;
  visual: 'pulse' | 'photoPulse' | 'radius';
}

const SLIDES: Slide[] = [
  { key: 'discover', titleKey: 'onboarding.slide1.title', bodyKey: 'onboarding.slide1.body', visual: 'pulse' },
  { key: 'share', titleKey: 'onboarding.slide2.title', bodyKey: 'onboarding.slide2.body', visual: 'photoPulse' },
  { key: 'levels', titleKey: 'onboarding.slide3.title', bodyKey: 'onboarding.slide3.body', visual: 'radius' },
];

/** Three value slides on the pulse motif (brief §12.1.2–4). */
export default function SlidesScreen() {
  const theme = useTheme();
  const t = useT();
  const router = useRouter();
  const { width } = useWindowDimensions();
  const listRef = useRef<FlatList<Slide>>(null);
  const [index, setIndex] = useState(0);
  const { colors } = theme;

  const next = () => {
    if (index < SLIDES.length - 1) {
      listRef.current?.scrollToIndex({ index: index + 1, animated: true });
    } else {
      router.push('/(onboarding)/permissions');
    }
  };

  return (
    <SafeAreaView style={[styles.safe, { backgroundColor: colors.background }]}>
      <View style={styles.skipRow}>
        <Button
          label={t('common.skip')}
          variant="ghost"
          size="sm"
          block={false}
          onPress={() => router.push('/(onboarding)/permissions')}
        />
      </View>
      <FlatList
        ref={listRef}
        data={SLIDES}
        horizontal
        pagingEnabled
        showsHorizontalScrollIndicator={false}
        keyExtractor={(item) => item.key}
        onMomentumScrollEnd={(event) => {
          setIndex(Math.round(event.nativeEvent.contentOffset.x / width));
        }}
        renderItem={({ item }) => (
          <View style={[styles.slide, { width }]}>
            <View style={styles.visual}>
              {item.visual === 'radius' ? (
                <RadiusDiagram
                  currentLabel="1200 m"
                  nextLabel={`${t('common.level')} 4 · 1800 m`}
                  height={220}
                />
              ) : (
                <PulseMotif size={230} rings={item.visual === 'photoPulse' ? 4 : 3} />
              )}
            </View>
            <AppText variant="headlineLg" align="center">
              {t(item.titleKey)}
            </AppText>
            <AppText variant="bodyLg" align="center" color={colors.onSurfaceVariant}>
              {t(item.bodyKey)}
            </AppText>
          </View>
        )}
      />
      <View style={styles.footer}>
        <View style={styles.dots}>
          {SLIDES.map((slide, dotIndex) => (
            <View
              key={slide.key}
              style={[
                styles.dot,
                {
                  backgroundColor: dotIndex === index ? colors.primary : colors.outlineVariant,
                  width: dotIndex === index ? 22 : 8,
                },
              ]}
            />
          ))}
        </View>
        <Button label={t('common.next')} onPress={next} />
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  skipRow: { flexDirection: 'row', justifyContent: 'flex-end', paddingHorizontal: 16 },
  slide: { flex: 1, paddingHorizontal: 32, justifyContent: 'center', gap: 12 },
  visual: { alignItems: 'center', justifyContent: 'center', height: 260, marginBottom: 12 },
  footer: { padding: 24, gap: 20 },
  dots: { flexDirection: 'row', justifyContent: 'center', gap: 6 },
  dot: { height: 8, borderRadius: 4 },
});
