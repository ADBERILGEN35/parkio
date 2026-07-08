import { Icon } from '@parkio/ui';
import { Link } from 'react-router-dom';
import { AuthSplitLayout } from '@/pages/auth/AuthSplitLayout';

type LegalKind = 'terms' | 'privacy';

const COPY: Record<
  LegalKind,
  { title: string; subtitle: string; body: string[] }
> = {
  terms: {
    title: 'Terms of Service',
    subtitle: 'Hosted-beta terms placeholder',
    body: [
      'These terms are a hosted-beta placeholder, not final legal advice or a final public-launch legal review.',
      'Parkio beta access, supported areas, features, invite timing, and availability may change during testing.',
      'If you use Parkio during beta, share parking information responsibly, follow local parking laws, and do not rely on Parkio as a final legal or safety authority.',
      'Questions or deletion requests can be sent to privacy@parkio.dev while the beta support process is being finalized.',
    ],
  },
  privacy: {
    title: 'Privacy Policy',
    subtitle: 'Hosted-beta privacy placeholder',
    body: [
      'This privacy page is a hosted-beta placeholder, not final legal review for public launch.',
      'For the beta waitlist, Parkio stores your email, consent receipt time, optional city or general area, optional role, source, creation time, and privacy-safe hashes of IP address and user agent for duplicate handling and abuse protection.',
      'Parkio uses waitlist data for beta communication, invite planning, launch-area planning, duplicate prevention, and abuse controls. It is not sold.',
      'To ask about waitlist data or request deletion, contact privacy@parkio.dev. During beta, deletion and support handling may be manual.',
    ],
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
        {body.map((paragraph) => (
          <p key={paragraph} className="m-0 text-body-md text-on-surface-variant">
            {paragraph}
          </p>
        ))}
        <Link
          to="/"
          className="inline-flex w-full items-center justify-center gap-xs rounded-full bg-primary px-lg py-sm text-label-lg font-semibold text-on-primary hover:bg-primary/90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
        >
          Back to Parkio
          <Icon name="arrow_back" className="text-[18px] leading-none" />
        </Link>
      </div>
    </AuthSplitLayout>
  );
}
