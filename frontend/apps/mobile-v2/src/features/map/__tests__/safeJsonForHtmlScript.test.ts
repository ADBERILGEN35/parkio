import { safeJsonForHtmlScript, buildParkioDispatchScript } from '../safeJsonForHtmlScript';

describe('safeJsonForHtmlScript (PA-04 reverse boundary)', () => {
  it('escapes script-breaking sequences', () => {
    const hostile = {
      label: '</script><script>alert(1)</script>',
      quote: `"'\\\n`,
      ls: '\u2028',
      ps: '\u2029',
    };
    const embedded = safeJsonForHtmlScript(hostile);
    expect(embedded).not.toMatch(/</);
    expect(embedded).not.toContain('</script>');
    expect(embedded).toContain('\\u003c');
    expect(embedded).toContain('\\u2028');
    expect(embedded).toContain('\\u2029');
    expect(JSON.parse(embedded)).toEqual(hostile);
  });

  it('buildParkioDispatchScript keeps a constant executable template', () => {
    const script = buildParkioDispatchScript({
      op: 'setDestinationMarker',
      marker: { lat: 1, lng: 2, label: '</script>' },
    });
    expect(script.startsWith('window.__parkio_dispatch(')).toBe(true);
    expect(script.endsWith('); true;')).toBe(true);
    expect(script).toContain('\\"');
  });
});
