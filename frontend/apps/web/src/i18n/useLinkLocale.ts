import { useEffect } from 'react';
import { useSearchParams } from 'react-router-dom';
import { localeFromSearchParam } from './localeFromSearchParam';
import { useLocaleStore } from './localeStore';

/**
 * Applies `?lang=` (tr|en) from an auth e-mail link as the UI language, the same way the
 * register and verify pages do: like a manual switch, it also updates `<html lang>` and the
 * stored preference. An unknown value is ignored (CL-F21).
 */
export function useLinkLocale(): void {
  const [searchParams] = useSearchParams();
  const setLocale = useLocaleStore((s) => s.setLocale);

  useEffect(() => {
    const linkLocale = localeFromSearchParam(searchParams.get('lang'));
    if (linkLocale) {
      setLocale(linkLocale);
    }
  }, [searchParams, setLocale]);
}
