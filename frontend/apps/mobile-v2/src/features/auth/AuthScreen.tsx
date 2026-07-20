import type { ReactNode } from 'react';
import {
  Image,
  Keyboard,
  KeyboardAvoidingView,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  View,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { AppText } from '@/components/ui/AppText';
import { ScreenHeader } from '@/components/ui/ScreenHeader';
import { useTheme } from '@/theme/ThemeProvider';

export interface AuthScreenProps {
  title: string;
  subtitle?: string;
  children: ReactNode;
  showBack?: boolean;
}

/** Single-column auth scaffold: logo, headline, keyboard-aware form column. */
export function AuthScreen({ title, subtitle, children, showBack = true }: AuthScreenProps) {
  const theme = useTheme();
  return (
    <SafeAreaView style={[styles.safe, { backgroundColor: theme.colors.background }]}>
      <ScreenHeader title="" showBack={showBack} />
      <KeyboardAvoidingView
        style={styles.flex}
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
      >
        <ScrollView
          contentContainerStyle={styles.scroll}
          keyboardShouldPersistTaps="handled"
          showsVerticalScrollIndicator={false}
        >
          <Pressable onPress={Keyboard.dismiss} accessible={false}>
            <View style={styles.header}>
              <Image
                source={require('../../../assets/images/icon.png')}
                style={styles.logo}
                accessibilityLabel="Parkio"
              />
              <AppText variant="headlineLg">{title}</AppText>
              {subtitle ? (
                <AppText variant="bodyMd" color={theme.colors.onSurfaceVariant}>
                  {subtitle}
                </AppText>
              ) : null}
            </View>
            <View style={styles.form}>{children}</View>
          </Pressable>
        </ScrollView>
      </KeyboardAvoidingView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  flex: { flex: 1 },
  scroll: { padding: 24, paddingTop: 8 },
  header: { gap: 6, marginBottom: 24 },
  logo: { width: 52, height: 52, borderRadius: 14, marginBottom: 10 },
  form: { gap: 16 },
});
