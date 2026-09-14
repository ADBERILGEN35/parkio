import { useRouter } from 'expo-router';
import { ConfirmModal } from '@/components/ui/ConfirmModal';
import {
  authGateBodyKey,
  authGateTitleKey,
  setPendingAuthGateIntent,
  type AuthGateIntent,
  type AuthGateResumeMeta,
} from '@/features/auth/authGateIntents';
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
 * Registration CTA omitted until registration mode is verified OPEN (Wave 6).
 */
export function AuthGate({ visible, intent, resume, onDismiss }: AuthGateProps) {
  const t = useT();
  const router = useRouter();

  const onLogin = () => {
    setPendingAuthGateIntent({ intent, ...resume });
    onDismiss();
    router.push('/(auth)/login');
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
      confirmVariant="primary"
    />
  );
}
