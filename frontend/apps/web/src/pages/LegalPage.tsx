import { Icon } from '@parkio/ui';
import { Link } from 'react-router-dom';
import { AuthSplitLayout } from '@/pages/auth/AuthSplitLayout';

type LegalKind = 'terms' | 'privacy';

const COPY: Record<
  LegalKind,
  { title: string; subtitle: string; body: string }
> = {
  terms: {
    title: 'Terms of Service',
    subtitle: 'Parkio hosted beta',
    body:
      'By using Parkio during the hosted beta you agree to share parking information responsibly, ' +
      'follow local parking laws, and treat other community members with respect. ' +
      'Service availability may change during the beta. Operators must publish final legal terms before public launch.',
  },
  privacy: {
    title: 'Privacy Policy',
    subtitle: 'Parkio hosted beta',
    body:
      'Parkio processes account, location, and parking-spot data to provide the service. ' +
      'We do not sell personal data. Location is used only for parking discovery and spot sharing features you initiate. ' +
      'Operators must publish a complete privacy policy before public launch.',
  },
};

export function TermsPage() {
  return <LegalPage kind="terms" />;
}

export function PrivacyPage() {
  return <LegalPage kind="privacy" />;
}

function LegalPage({ kind }: { kind: LegalKind }) {
  const { title, subtitle, body } = COPY[kind];
  return (
    <AuthSplitLayout title={title} subtitle={subtitle}>
      <div className="flex flex-col gap-md">
        <p className="m-0 text-body-md text-on-surface-variant">{body}</p>
        <Link
          to="/register"
          className="inline-flex w-full items-center justify-center gap-xs rounded-full bg-primary px-lg py-sm text-label-lg font-semibold text-on-primary hover:bg-primary/90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
        >
          Back to registration
          <Icon name="arrow_back" className="text-[18px] leading-none" />
        </Link>
      </div>
    </AuthSplitLayout>
  );
}