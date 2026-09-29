import {
  shouldAllowMapWebViewNavigation,
  shouldAllowMapWebViewOpenWindow,
} from '../mapWebViewNavigation';

describe('mapWebViewNavigation (PA-05)', () => {
  it('allows initial inline document', () => {
    expect(shouldAllowMapWebViewNavigation({ url: 'about:blank', isTopFrame: true })).toBe(
      true,
    );
    expect(shouldAllowMapWebViewNavigation({ url: '', isTopFrame: true })).toBe(true);
    expect(shouldAllowMapWebViewNavigation({ url: 'about:srcdoc', isTopFrame: true })).toBe(
      true,
    );
  });

  it('blocks unexpected https/http top-level navigation', () => {
    expect(
      shouldAllowMapWebViewNavigation({
        url: 'https://evil.example/',
        isTopFrame: true,
      }),
    ).toBe(false);
    expect(
      shouldAllowMapWebViewNavigation({
        url: 'http://evil.example/',
        isTopFrame: true,
      }),
    ).toBe(false);
  });

  it('blocks javascript: and data: and file:', () => {
    expect(
      shouldAllowMapWebViewNavigation({ url: 'javascript:alert(1)', isTopFrame: true }),
    ).toBe(false);
    expect(
      shouldAllowMapWebViewNavigation({
        url: 'data:text/html,hi',
        isTopFrame: true,
      }),
    ).toBe(false);
    expect(
      shouldAllowMapWebViewNavigation({ url: 'file:///etc/passwd', isTopFrame: true }),
    ).toBe(false);
  });

  it('blocks custom schemes and nested frames', () => {
    expect(
      shouldAllowMapWebViewNavigation({ url: 'myapp://open', isTopFrame: true }),
    ).toBe(false);
    expect(
      shouldAllowMapWebViewNavigation({ url: 'about:blank', isTopFrame: false }),
    ).toBe(false);
  });

  it('blocks new window requests', () => {
    expect(shouldAllowMapWebViewOpenWindow()).toBe(false);
  });
});
