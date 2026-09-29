import { useRouter } from 'expo-router';
import { ConfirmModal } from '@/components/ui/ConfirmModal';
import {
  authGateBodyKey,
  authGateTitleKey,
  setPendingAuthGateIntent,
  type AuthGateIntent,
  type AuthGateResumeMeta,
} from '@/features/auth/authGateIntents';
import {
  isRegistrationSignupAllowed,
  useRegistrationMode,
} from '@/features/auth/useRegistrationMode';
import { useT } from '@/i18n/LocaleProvider';

export interface AuthGateProps {
  visible: boolean;
  intent: AuthGateIntent;
  /** Optional non-sensitive resume metadata (e.g. public facility id). */
  resume?: Omit<AuthGateResumeMeta, 'intent'>;
  onDismiss: () => void;
}

/**
 * Native mobile AuthGate — sign-in primary, dismiss tertiary.
 * No signup CTA while registration mode is CLOSED.
 * Quiet "about registration" when CLOSED → existing closed-register screen.
 * Does not set pending intent when opening registration info (login path only).
 */
export function AuthGate({ visible, intent, resume, onDismiss }: AuthGateProps) {
  const t = useT();
  const router = useRouter();
  const registrationMode = useRegistrationMode();
  const signupAllowed = isRegistrationSignupAllowed(registrationMode);

  const onLogin = () => {
    setPendingAuthGateIntent({ intent, ...resume });
    onDismiss();
    router.push('/(auth)/login');
  };

  const onRegistrationAbout = () => {
    // Do not set pending contribute intent — user is only reading closed-registration info.
    onDismiss();
    router.push('/(auth)/register');
  };

  return (
    <ConfirmModal
      visible={visible}
      title={t(authGateTitleKey(intent))}
      body={t(authGateBodyKey(intent))}
      confirmLabel={t('authGate.login')}
      cancelLabel={t('authGate.dismiss')}
      onConfirm={onLogin}
      onCancel={onDismiss}
      tertiaryLabel={signupAllowed ? undefined : t('authGate.registrationAbout')}
      onTertiary={signupAllowed ? undefined : onRegistrationAbout}
      confirmVariant="primary"
    />
  );
}
