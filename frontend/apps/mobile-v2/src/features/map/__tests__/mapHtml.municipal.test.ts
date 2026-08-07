import { buildMapHtml } from '../mapHtml';

const colors = {
  fresh: '#0050CB',
  aging: '#A06500',
  expiring: '#BA1A1A',
  track: '#DCE9FF',
  pillBg: '#FFFFFF',
  pillText: '#0B1C30',
  muted: '#727687',
  primary: '#0050CB',
  userDot: '#0050CB',
  userHalo: 'rgba(0,80,203,0.18)',
};

describe('buildMapHtml municipal bridge', () => {
  const html = buildMapHtml({
    center: { lat: 38.42, lng: 27.14 },
    zoom: 12,
    mode: 'light',
    colors,
  });

  it('keeps community setSpots dispatch', () => {
    expect(html).toContain("message.op === 'setSpots'");
    expect(html).toContain('function setSpots');
  });

  it('adds a separate municipal facilities dispatch path', () => {
    expect(html).toContain("message.op === 'setMunicipalFacilities'");
    expect(html).toContain('function setMunicipalFacilities');
    expect(html).toContain("message.op === 'setSelectedMunicipal'");
    expect(html).toContain('muniMarkers');
  });

  it('posts municipalTap separately from spotTap', () => {
    expect(html).toContain("type: 'municipalTap'");
    expect(html).toContain("type: 'spotTap'");
  });

  it('uses square garage pin styling distinct from community pills', () => {
    expect(html).toContain('.pk-muni');
    expect(html).toContain('.pk-muni-pin');
    expect(html).toContain('pk-muni-kind-stale_live');
  });

  it('ignores non-finite municipal coordinates', () => {
    expect(html).toContain('!isFinite(data.lat)');
  });

  it('adds destination marker and recommendation highlight ops for SPA-09', () => {
    expect(html).toContain("message.op === 'setDestinationMarker'");
    expect(html).toContain('function setDestinationMarker');
    expect(html).toContain("message.op === 'setRecommendedHighlights'");
    expect(html).toContain('function setRecommendedHighlights');
    expect(html).toContain('.pk-dest');
    expect(html).toContain('pk-recommended');
  });
});
