import { Icon } from '@parkio/ui';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { AuthSplitLayout } from '@/pages/auth/AuthSplitLayout';

type LegalKind = 'terms' | 'privacy';

export function TermsPage() {
  return <LegalPage kind="terms" />;
}

export function PrivacyPage() {
  return <LegalPage kind="privacy" />;
}

function LegalPage({ kind }: { kind: LegalKind }) {
  const { t, i18n } = useTranslation('legal');
  const title = t(`${kind}.title`);
  const subtitle = t(`${kind}.subtitle`);
  const body = i18n.t(`${kind}.body`, { ns: 'legal', returnObjects: true });
  const paragraphs = Array.isArray(body) ? (body as string[]) : [];

  return (
    <AuthSplitLayout title={title} subtitle={subtitle}>
      <div className="flex flex-col gap-md">
        {paragraphs.map((paragraph) => (
          <p key={paragraph} className="m-0 text-body-md text-on-surface-variant">
            {paragraph}
          </p>
        ))}
        <Link
          to="/"
          className="inline-flex w-full items-center justify-center gap-xs rounded-full bg-primary px-lg py-sm text-label-lg font-semibold text-on-primary hover:bg-primary/90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
        >
          {t('backToParkio')}
          <Icon name="arrow_back" className="text-[18px] leading-none" />
        </Link>
      </div>
    </AuthSplitLayout>
  );
}
