import { mkdirSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { expect, type Page, type TestInfo } from '@playwright/test';
import { KNOWN_ISSUES } from './known-issues';

/**
 * Browser-measured accessibility checks (CL-F30): axe-core in real Chromium plus a keyboard walk.
 *
 * axe-core is resolved through jest-axe, the unit tests' axe, so the browser and jsdom run the same
 * rules engine without another dependency. jsdom cannot compute colours, so colour contrast is only
 * measured here.
 */
const requireFromHere = createRequire(import.meta.url);
const AXE_SOURCE = createRequire(requireFromHere.resolve('jest-axe')).resolve('axe-core/axe.min.js');

/** Served from the page's own origin, so a `script-src 'self'` policy (the marketing site) allows it. */
const AXE_PATH = '/__a11y__/axe.min.js';

/** WCAG 2.0, 2.1 and 2.2 level A and AA rules. Best-practice rules are not part of the measurement. */
export const WCAG_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'];

export type Locale = 'tr' | 'en';

export interface AxeNode {
  target: string[];
  html: string;
  failureSummary?: string;
}

export interface AxeRuleResult {
  id: string;
  impact: string | null;
  help: string;
  helpUrl: string;
  nodes: AxeNode[];
}

interface AxeRun {
  testEngine: { version: string };
  violations: AxeRuleResult[];
  incomplete: AxeRuleResult[];
  passes: { id: string }[];
}

export interface PageMeasurement {
  page: string;
  locale: Locale;
  url: string;
  htmlLang: string | null;
  /** A main landmark is best practice, not a WCAG A/AA rule; it is recorded, not asserted. */
  mainLandmark: boolean;
  axeVersion: string;
  violations: AxeRuleResult[];
  /** Violations listed in known-issues.ts: measured and documented, not fixed in this change. */
  knownViolations: AxeRuleResult[];
  /** Results axe could not decide (for example contrast over an image): listed for manual review, not failed. */
  incomplete: { id: string; nodes: number; targets: string[]; reasons: Record<string, number> }[];
  passedRules: number;
}

async function injectAxe(page: Page) {
  await page.route(`**${AXE_PATH}`, (route) => route.fulfill({ path: AXE_SOURCE, contentType: 'text/javascript' }));
  await page.addScriptTag({ url: AXE_PATH });
}

/**
 * Runs axe on the current page and records the result; fails on violations that are not known.
 * `lang` is the language the page's content is in: the selected locale for translated pages, or the
 * page's own language for single-language pages (WCAG 3.1.1 asks that <html lang> match the content).
 */
export async function measurePage(page: Page, testInfo: TestInfo, name: string, locale: Locale, lang: string = locale) {
  await injectAxe(page);
  const run = (await page.evaluate(async (tags) => {
    const axe = (window as unknown as { axe: { run: (ctx: Document, opts: unknown) => Promise<unknown> } }).axe;
    return axe.run(document, { runOnly: { type: 'tag', values: tags }, resultTypes: ['violations', 'incomplete'] });
  }, WCAG_TAGS)) as AxeRun;

  const known = KNOWN_ISSUES.filter((issue) => issue.page === name || issue.page === '*');
  const isKnown = (rule: AxeRuleResult, node: AxeNode) =>
    known.some((issue) => issue.rule === rule.id && node.target.join(' ').includes(issue.target));
  const split = (rule: AxeRuleResult, keep: (node: AxeNode) => boolean) => ({
    ...rule,
    nodes: rule.nodes.filter(keep),
  });
  const violations = run.violations.map((rule) => split(rule, (node) => !isKnown(rule, node))).filter((r) => r.nodes.length);
  const knownViolations = run.violations.map((rule) => split(rule, (node) => isKnown(rule, node))).filter((r) => r.nodes.length);

  const measurement: PageMeasurement = {
    page: name,
    locale,
    url: new URL(page.url()).pathname + new URL(page.url()).search,
    htmlLang: await page.evaluate(() => document.documentElement.getAttribute('lang')),
    mainLandmark: await page.evaluate(() => document.querySelector('main, [role="main"]') !== null),
    axeVersion: run.testEngine.version,
    violations,
    knownViolations,
    incomplete: run.incomplete.map((rule) => ({
      id: rule.id,
      nodes: rule.nodes.length,
      targets: rule.nodes.map((node) => node.target.join(' ')),
      reasons: rule.nodes.reduce<Record<string, number>>((acc, node) => {
        // The first line after "Fix any of the following:" is axe's reason for not deciding.
        const reason = (node.failureSummary ?? '').split('\n').map((line) => line.trim()).filter(Boolean)[1] ?? 'unspecified';
        acc[reason] = (acc[reason] ?? 0) + 1;
        return acc;
      }, {}),
    })),
    passedRules: run.passes.length,
  };
  writeReport(testInfo, `${testInfo.project.name}-${name}-${locale}`, measurement);

  expect.soft(measurement.htmlLang, `${name} (${locale}): <html lang>`).toBe(lang);
  expect(
    violations.map((rule) => `${rule.id} (${rule.impact}): ${rule.nodes.map((n) => n.target.join(' ')).join(', ')}`),
    `${name} (${locale}): axe WCAG A/AA violations`,
  ).toEqual([]);
  return measurement;
}

export interface FocusStop {
  index: number;
  element: string;
  name: string;
  /**
   * The focused element looks different from its unfocused self in a way a user can see: an outline
   * that is visible (a style, a width and a colour that is not transparent) and differs from the
   * unfocused outline, or a box-shadow, border, background or underline that differs. Shadows are
   * compared by their visible layers only, and measured after the element's transitions finish: a
   * focus ring that fades in counts, while a transparent outline (Tailwind's `focus:outline-none`) or a
   * ring transition that has not started yet does not.
   */
  indicator: boolean;
}

/**
 * Walks the page with Tab and records each focus stop. Fails when a stop has no visible focus
 * indicator, when focus never moves, or when it is trapped: it neither leaves the page nor comes back
 * to the first stop within the limit. Coming back to the first stop is a cycle through the page (for
 * example sonner returns focus to where it was when focus leaves the toaster); it is recorded.
 */
export async function keyboardWalk(page: Page, testInfo: TestInfo, name: string, locale: Locale, limit = 60) {
  await page.locator('body').click({ position: { x: 1, y: 1 } });
  await page.evaluate(() => {
    (document.activeElement as HTMLElement | null)?.blur();
    // The visible layers of a computed box-shadow: a layer with a transparent colour, or with no
    // offset, blur or spread, draws nothing ("none" and "rgba(0, 0, 0, 0) 0px 0px 0px 0px" look alike).
    const visibleShadow = (value: string) => {
      if (value === 'none') return 'none';
      const layers = value.split(/,(?![^(]*\))/).map((layer) => layer.trim());
      const visible = layers.filter((layer) => {
        const colour = layer.match(/rgba?\([^)]*\)/)?.[0] ?? '';
        const alpha = colour.startsWith('rgba') ? parseFloat(colour.split(',')[3] ?? '1') : 1;
        const lengths = (layer.replace(colour, '').match(/-?[\d.]+px/g) ?? []).map((px) => parseFloat(px));
        return alpha > 0 && lengths.some((length) => length !== 0);
      });
      return visible.length ? visible.join(', ') : 'none';
    };
    (window as unknown as { __a11yVisibleShadow: (value: string) => string }).__a11yVisibleShadow = visibleShadow;
    // Unfocused styles of every element that can take focus, to compare with its focused style.
    const look = (el: Element) => {
      const s = getComputedStyle(el);
      return {
        outline: [s.outlineStyle, s.outlineWidth, s.outlineColor, s.outlineOffset].join('|'),
        rest: [visibleShadow(s.boxShadow), s.borderTopColor, s.borderBottomColor, s.backgroundColor, s.textDecorationLine].join('|'),
      };
    };
    const store = new WeakMap<Element, { outline: string; rest: string }>();
    document.querySelectorAll('*').forEach((el) => store.set(el, look(el)));
    (window as unknown as { __a11yUnfocused: WeakMap<Element, { outline: string; rest: string }> }).__a11yUnfocused =
      store;
  });
  const stops: FocusStop[] = [];
  let leftPage = false;
  let cycled = false;
  for (let index = 0; index < limit; index++) {
    await page.keyboard.press('Tab');
    const stop = await page.evaluate(async (i) => {
      const el = document.activeElement as HTMLElement | null;
      if (!el || el === document.body || el === document.documentElement) return null;
      if (el.dataset.a11yFirstStop === 'true') return 'first';
      if (i === 0) el.dataset.a11yFirstStop = 'true';
      // Let focus transitions (a ring that fades in) finish; an endless animation is cut off at 1 s.
      await Promise.race([
        Promise.all(el.getAnimations().map((animation) => animation.finished.catch(() => undefined))),
        new Promise((resolve) => setTimeout(resolve, 1000)),
      ]);
      const style = getComputedStyle(el);
      const transparent = (colour: string) =>
        colour === 'transparent' || /rgba\([^)]*,\s*0(\.0+)?\)$/.test(colour.replace(/\s+/g, ' ').trim());
      const outlineLook = [style.outlineStyle, style.outlineWidth, style.outlineColor, style.outlineOffset].join('|');
      const visibleShadow = (window as unknown as { __a11yVisibleShadow: (value: string) => string }).__a11yVisibleShadow;
      const focusedLook = [visibleShadow(style.boxShadow), style.borderTopColor, style.borderBottomColor, style.backgroundColor, style.textDecorationLine].join('|');
      const unfocused = (
        window as unknown as { __a11yUnfocused: WeakMap<Element, { outline: string; rest: string }> }
      ).__a11yUnfocused.get(el);
      const outline =
        style.outlineStyle !== 'none' &&
        parseFloat(style.outlineWidth) > 0 &&
        !transparent(style.outlineColor) &&
        (unfocused === undefined || outlineLook !== unfocused.outline);
      const shadow = unfocused !== undefined && focusedLook !== unfocused.rest;
      const label =
        el.getAttribute('aria-label') ?? el.getAttribute('title') ?? (el.textContent ?? '').trim().slice(0, 60);
      const id = el.id ? `#${el.id}` : '';
      return { index: i, element: `${el.tagName.toLowerCase()}${id}`, name: label, indicator: outline || shadow };
    }, index);
    if (stop === 'first') {
      cycled = true;
      break;
    }
    if (!stop) {
      leftPage = true;
      break;
    }
    stops.push(stop);
  }
  await page.evaluate(() => document.querySelector('[data-a11y-first-stop]')?.removeAttribute('data-a11y-first-stop'));
  writeReport(testInfo, `${testInfo.project.name}-${name}-${locale}-keyboard`, { page: name, locale, stops, leftPage, cycled });
  if (cycled) testInfo.annotations.push({ type: 'keyboard', description: `${name} (${locale}): focus cycles back to the first stop` });
  expect(stops.length, `${name} (${locale}): Tab reaches at least one control`).toBeGreaterThan(0);
  expect(leftPage || cycled, `${name} (${locale}): focus leaves the page or cycles within ${limit} Tab presses (no trap)`).toBe(true);
  expect(
    stops.filter((stop) => !stop.indicator).map((stop) => `${stop.index}: ${stop.element} "${stop.name}"`),
    `${name} (${locale}): focus stops without a visible indicator`,
  ).toEqual([]);
  return stops;
}

export interface ToastColour {
  type: string;
  color: string;
  background: string;
  ratio: number;
}

/**
 * Measures the text contrast of all four rich toast types in the live toaster. A visible success toast
 * is copied with data-type info, warning and error into the same toaster, so the copies get sonner's
 * injected CSS and the app's variables; colours are read from the title and the toast, then removed.
 */
export async function measureToastPalette(page: Page, testInfo: TestInfo, locale: Locale) {
  const success = page.locator('[data-sonner-toast][data-type="success"]').first();
  await expect(success).toBeVisible();
  await success.hover();
  const colours = (await page.evaluate(() => {
    const original = document.querySelector<HTMLElement>('[data-sonner-toast][data-type="success"]');
    if (!original?.parentElement) return [];
    const toasts = [original];
    for (const type of ['info', 'warning', 'error']) {
      const copy = original.cloneNode(true) as HTMLElement;
      copy.dataset.type = type;
      original.parentElement.appendChild(copy);
      toasts.push(copy);
    }
    const channel = (v: number) => {
      const c = v / 255;
      return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
    };
    const luminance = (rgb: string) => {
      const [r, g, b] = (rgb.match(/[\d.]+/g) ?? []).map(Number);
      return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
    };
    const result = toasts.map((toast) => {
      const title = toast.querySelector<HTMLElement>('[data-title]') ?? toast;
      const color = getComputedStyle(title).color;
      const background = getComputedStyle(toast).backgroundColor;
      const [hi, lo] = [luminance(color), luminance(background)].sort((a, b) => b - a);
      return { type: toast.dataset.type ?? '', color, background, ratio: Math.round(((hi + 0.05) / (lo + 0.05)) * 100) / 100 };
    });
    toasts.slice(1).forEach((copy) => copy.remove());
    return result;
  })) as ToastColour[];
  writeReport(testInfo, `${testInfo.project.name}-toast-palette-${locale}`, { locale, colours });
  expect(colours.map((c) => c.type)).toEqual(['success', 'info', 'warning', 'error']);
  expect(
    colours.filter((c) => c.ratio < 4.5).map((c) => `${c.type}: ${c.color} on ${c.background} = ${c.ratio}:1`),
    `rich toast text below WCAG AA 4.5:1 (${locale})`,
  ).toEqual([]);
  return colours;
}

function writeReport(testInfo: TestInfo, file: string, data: unknown) {
  const dir = path.join(testInfo.config.rootDir, '..', 'test-results', 'a11y');
  mkdirSync(dir, { recursive: true });
  const body = JSON.stringify(data, null, 2);
  writeFileSync(path.join(dir, `${file}.json`), body);
  void testInfo.attach(file, { body, contentType: 'application/json' });
}
