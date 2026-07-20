import { useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { useRouter } from 'expo-router';
import { SafeAreaView } from 'react-native-safe-area-context';
import * as Location from 'expo-location';
import { useCameraPermissions } from 'expo-camera';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import Animated, { FadeInRight, FadeOutLeft } from 'react-native-reanimated';
import { AppText } from '@/components/ui/AppText';
import { Button } from '@/components/ui/Button';
import { Card } from '@/components/ui/Card';
import { PulseMotif } from '@/components/ui/PulseMotif';
import { useT } from '@/i18n/LocaleProvider';
import type { TranslationKey } from '@/i18n/translations';
import { requestPushPermissions } from '@/services/pushNotifications';
import { useTheme } from '@/theme/ThemeProvider';

type PermissionKind = 'location' | 'notifications' | 'camera';

interface PrimingCard {
  kind: PermissionKind;
  icon: keyof typeof MaterialCommunityIcons.glyphMap;
  titleKey: TranslationKey;
  bodyKey: TranslationKey;
}

const CARDS: PrimingCard[] = [
  {
    kind: 'location',
    icon: 'map-marker-outline',
    titleKey: 'onboarding.permission.location.title',
    bodyKey: 'onboarding.permission.location.body',
  },
  {
    kind: 'notifications',
    icon: 'bell-outline',
    titleKey: 'onboarding.permission.notifications.title',
    bodyKey: 'onboarding.permission.notifications.body',
  },
  {
    kind: 'camera',
    icon: 'camera-outline',
    titleKey: 'onboarding.permission.camera.title',
    bodyKey: 'onboarding.permission.camera.body',
  },
];

/** Permission priming BEFORE system dialogs (brief §12.1.5–7). */
export default function PermissionsScreen() {
  const theme = useTheme();
  const t = useT();
  const router = useRouter();
  const [index, setIndex] = useState(0);
  const [requesting, setRequesting] = useState(false);
  const [, requestCameraPermission] = useCameraPermissions();
  const { colors } = theme;

  const card = CARDS[index];

  const advance = () => {
    if (index < CARDS.length - 1) {
      setIndex(index + 1);
    } else {
      router.push('/(onboarding)/welcome');
    }
  };

  const allow = async () => {
    setRequesting(true);
    try {
      if (card.kind === 'location') {
        await Location.requestForegroundPermissionsAsync();
      } else if (card.kind === 'notifications') {
        await requestPushPermissions();
      } else {
        await requestCameraPermission();
      }
    } finally {
      setRequesting(false);
      advance();
    }
  };

  return (
    <SafeAreaView style={[styles.safe, { backgroundColor: colors.background }]}>
      <View style={styles.center}>
        <Animated.View
          key={card.kind}
          entering={FadeInRight.duration(250)}
          exiting={FadeOutLeft.duration(200)}
          style={styles.cardWrap}
        >
          <Card padding={28} radius={28} style={styles.card}>
            <View style={styles.iconWrap}>
              <PulseMotif size={120} rings={3} style={styles.pulse} />
              <View style={[styles.iconBubble, { backgroundColor: colors.primaryFixed }]}>
                <MaterialCommunityIcons name={card.icon} size={30} color={colors.primary} />
              </View>
            </View>
            <AppText variant="headlineMd" align="center">
              {t(card.titleKey)}
            </AppText>
            <AppText variant="bodyMd" align="center" color={colors.onSurfaceVariant}>
              {t(card.bodyKey)}
            </AppText>
            <View style={styles.actions}>
              <Button label={t('common.allow')} onPress={allow} loading={requesting} />
              <Button label={t('common.notNow')} variant="ghost" onPress={advance} disabled={requesting} />
            </View>
          </Card>
        </Animated.View>
      </View>
      <View style={styles.dots}>
        {CARDS.map((item, dotIndex) => (
          <View
            key={item.kind}
            style={[
              styles.dot,
              { backgroundColor: dotIndex === index ? colors.primary : colors.outlineVariant },
            ]}
          />
        ))}
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1, padding: 24 },
  center: { flex: 1, justifyContent: 'center' },
  cardWrap: {},
  card: { gap: 10 },
  iconWrap: { alignItems: 'center', justifyContent: 'center', height: 130, marginBottom: 4 },
  pulse: { position: 'absolute' },
  iconBubble: {
    width: 64,
    height: 64,
    borderRadius: 32,
    alignItems: 'center',
    justifyContent: 'center',
  },
  actions: { marginTop: 14, gap: 4 },
  dots: { flexDirection: 'row', justifyContent: 'center', gap: 6, paddingBottom: 12 },
  dot: { width: 8, height: 8, borderRadius: 4 },
});
