import { Icon } from '@parkio/ui';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { useAuthStore } from '@/auth/store';

export function NotFoundPage() {
  const { t } = useTranslation('common');
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);
  const target = isAuthenticated ? '/map' : '/login';
  const label = isAuthenticated ? t('notFound.goToMap') : t('notFound.goToLogin');

  return (
    <main className="flex min-h-dvh items-center justify-center bg-background px-md py-xl text-on-background">
      <section
        data-route-focus
        className="w-full max-w-lg rounded-3xl border border-outline-variant/50 bg-surface-container-lowest p-xl text-center shadow-soft"
      >
        <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-2xl bg-primary-fixed text-primary">
          <Icon name="wrong_location" className="text-[32px] leading-none" />
        </div>
        <p className="m-0 mt-md text-label-md font-semibold uppercase tracking-wider text-primary">
          {t('notFound.code')}
        </p>
        <h1 className="m-0 mt-xs text-headline-md text-on-surface">{t('notFound.title')}</h1>
        <p className="m-0 mt-sm text-body-md text-on-surface-variant">{t('notFound.description')}</p>
        <Link
          to={target}
          className="mt-lg inline-flex items-center justify-center gap-sm rounded-full bg-primary px-lg py-sm text-label-md text-on-primary shadow-sm transition-all duration-std hover:bg-primary/90 hover:shadow-md focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 motion-safe:active:scale-95"
        >
          <Icon name={isAuthenticated ? 'map' : 'login'} className="text-[18px] leading-none" />
          {label}
        </Link>
      </section>
    </main>
  );
}
