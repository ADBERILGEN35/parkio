import type { ReactNode } from 'react';
import { StyleSheet, View } from 'react-native';
import { useRouter } from 'expo-router';
import { AppText } from './AppText';
import { IconButton } from './IconButton';
import { useT } from '@/i18n/LocaleProvider';

export interface ScreenHeaderProps {
  title: string;
  /** Right-side accessory (chip, action). */
  trailing?: ReactNode;
  onBack?: () => void;
  showBack?: boolean;
}

/** Standard sub-screen header: back chevron + title + optional trailing. */
export function ScreenHeader({ title, trailing, onBack, showBack = true }: ScreenHeaderProps) {
  const router = useRouter();
  const t = useT();
  return (
    <View style={styles.row}>
      {showBack ? (
        <IconButton
          icon="arrow-left"
          size={40}
          variant="glassless"
          accessibilityLabel={t('common.back')}
          onPress={onBack ?? (() => (router.canGoBack() ? router.back() : router.replace('/')))}
        />
      ) : (
        <View style={styles.spacer} />
      )}
      <AppText variant="titleLg" style={styles.title} numberOfLines={1}>
        {title}
      </AppText>
      <View style={styles.trailing}>{trailing ?? <View style={styles.spacer} />}</View>
    </View>
  );
}

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 8,
    paddingVertical: 6,
    gap: 4,
  },
  title: { flex: 1, textAlign: 'center' },
  trailing: { minWidth: 40, alignItems: 'flex-end' },
  spacer: { width: 40, height: 40 },
});
