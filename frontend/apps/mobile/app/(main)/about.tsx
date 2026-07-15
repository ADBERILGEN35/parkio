import Constants from 'expo-constants';
import { Stack } from 'expo-router';
import { useState } from 'react';
import { Share, StyleSheet } from 'react-native';
import { BrandLockup } from '@/components/brand/BrandLockup';
import { Button, Card, Screen } from '@/components/ui';
import { AppText } from '@/components/ui/AppText';
import { appConfig } from '@/config/env';
import { buildInfo } from '@/config/buildInfo';
import { useLocale } from '@/i18n/LocaleProvider';
import { useToast } from '@/providers/ToastProvider';

export default function AboutScreen() {
  const { t } = useLocale();
  const toast = useToast();
  const [showTech, setShowTech] = useState(false);
  const version = Constants.expoConfig?.version ?? '1.0.0-rc2';
  const versionCode =
    Constants.expoConfig?.android?.versionCode ?? Constants.expoConfig?.ios?.buildNumber ?? '—';
  const shortSha = buildInfo.gitSha === 'unverified' ? 'unverified' : buildInfo.gitSha.slice(0, 7);

  const copyDiagnostics = async () => {
    const payload = [
      `Parkio ${version}`,
      `versionCode=${versionCode}`,
      `sha=${shortSha}`,
      `env=${appConfig.appEnv}`,
      `api=${appConfig.apiBaseUrl}`,
    ].join('\n');
    try {
      await Share.share({ message: payload });
      toast.showSuccess(t('Diagnostics copied.'));
    } catch {
      // user dismissed share sheet
    }
  };

  return (
    <>
      <Stack.Screen options={{ title: t('About the app') }} />
      <Screen contentStyle={styles.content} edges={['left', 'right', 'bottom']}>
        <BrandLockup />
        <AppText variant="body" tone="muted">
          {t('App version and build details for support.')}
        </AppText>
        <Card>
          <AppText variant="label">{t('Version')}</AppText>
          <AppText variant="body">{version}</AppText>
          <AppText variant="label" style={styles.mt}>
            {t('Build')}
          </AppText>
          <AppText variant="body">{shortSha}</AppText>
          <AppText variant="label" style={styles.mt}>
            {t('Environment')}
          </AppText>
          <AppText variant="body">{appConfig.appEnv}</AppText>
        </Card>
        <Button label={t('Copy diagnostics')} onPress={() => void copyDiagnostics()} />
        <Button
          label={t('Technical information')}
          variant="ghost"
          onPress={() => setShowTech((v) => !v)}
        />
        {showTech ? (
          <Card>
            <AppText variant="caption" tone="muted" selectable>
              versionCode={String(versionCode)}
            </AppText>
            <AppText variant="caption" tone="muted" selectable>
              gitSha={buildInfo.gitSha}
            </AppText>
            <AppText variant="caption" tone="muted" selectable>
              api={appConfig.apiBaseUrl}
            </AppText>
          </Card>
        ) : null}
      </Screen>
    </>
  );
}

const styles = StyleSheet.create({
  content: { gap: 16 },
  mt: { marginTop: 12 },
});
