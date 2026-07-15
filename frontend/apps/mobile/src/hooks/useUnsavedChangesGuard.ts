import { useNavigation } from 'expo-router';
import { useCallback, useEffect, useRef } from 'react';
import { Alert } from 'react-native';
import { useLocale } from '@/i18n/LocaleProvider';

/** Protects header, gesture, router and Android hardware-back navigation. */
export function useUnsavedChangesGuard(enabled: boolean) {
  const navigation = useNavigation();
  const bypassNextRef = useRef(false);
  const dialogOpenRef = useRef(false);
  const { t } = useLocale();

  useEffect(
    () =>
      navigation.addListener('beforeRemove', (event) => {
        if (!enabled || bypassNextRef.current) return;
        event.preventDefault();
        if (dialogOpenRef.current) return;
        dialogOpenRef.current = true;
        Alert.alert(
          t('Unsaved changes'),
          t('You have an unfinished parking spot submission. If you leave now, your progress will be lost.'),
          [
            { text: t('Stay'), style: 'cancel', onPress: () => (dialogOpenRef.current = false) },
            {
              text: t('Leave'),
              style: 'destructive',
              onPress: () => {
                dialogOpenRef.current = false;
                bypassNextRef.current = true;
                navigation.dispatch(event.data.action);
              },
            },
          ],
        );
      }),
    [enabled, navigation, t],
  );

  return useCallback(() => {
    bypassNextRef.current = true;
  }, []);
}

