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
  municipal: '#006C49',
  municipalGlyph: '#FFFFFF',
  communityGlyph: '#FFFFFF',
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

  it('supports fitBounds for public Explore framing', () => {
    expect(html).toContain("message.op === 'fitBounds'");
    expect(html).toContain('fitBounds(bounds');
  });

  it('posts municipalTap separately from spotTap', () => {
    expect(html).toContain("type: 'municipalTap'");
    expect(html).toContain("type: 'spotTap'");
  });

  it('renders municipal markers as GREEN P (category token, not freshness blue)', () => {
    expect(html).toContain('.pk-muni');
    expect(html).toContain('.pk-muni-pin');
    expect(html).toContain('.pk-muni-p');
    expect(html).toContain("class=\"pk-muni-p\">P</span>");
    expect(html).toContain('--muni-accent: #006C49');
    expect(html).not.toContain('pk-muni-kind-live, .pk-muni-kind-aging { --muni-accent: #0050CB');
    expect(html).not.toContain('M13 3H6v18h4v-6h3');
  });

  it('renders community markers as BLUE P with secondary freshness ring', () => {
    expect(html).toContain('.pk-spot-pin');
    expect(html).toContain('.pk-spot-p');
    expect(html).toContain("class=\"pk-spot-p\">P</span>");
    expect(html).toContain('--community-accent');
    expect(html).toContain('COLORS.fresh');
    expect(html).not.toContain('pk-time');
    expect(html).not.toContain('class="pk-pill"');
  });

  it('keeps destination and user location visually distinct from parking P markers', () => {
    expect(html).toContain('.pk-dest-pin');
    expect(html).toContain('background: #008069');
    expect(html).toContain('.pk-user');
    expect(html).toContain('--user-dot');
  });

  it('preserves selected-state chrome without recoloring category', () => {
    expect(html).toContain('.pk-muni-selected .pk-muni-pin');
    expect(html).toContain('.pk-selected .pk-spot-pin');
    expect(html).toContain('transform: scale(1.1)');
  });

  it('ignores non-finite municipal coordinates', () => {
    expect(html).toContain('!isFinite(data.lat)');
  });

  it('adds destination marker and recommendation highlight ops for SPA-09', () => {
    expect(html).toContain("message.op === 'setDestinationMarker'");
    expect(html).toContain('function setDestinationMarker');
    expect(html).toContain("message.op === 'setParkedCarMarker'");
    expect(html).toContain('function setParkedCarMarker');
    expect(html).toContain("message.op === 'setRecommendedHighlights'");
    expect(html).toContain('function setRecommendedHighlights');
    expect(html).toContain('.pk-dest');
    expect(html).toContain('pk-recommended');
  });
});

describe('buildMapHtml dark municipal glyph contrast', () => {
  it('uses dark ink on bright municipal green', () => {
    const darkHtml = buildMapHtml({
      center: { lat: 38.42, lng: 27.14 },
      zoom: 12,
      mode: 'dark',
      colors: {
        ...colors,
        municipal: '#6CF8BB',
        municipalGlyph: '#0B1626',
        fresh: '#4D8DFF',
        primary: '#4D8DFF',
      },
    });
    expect(darkHtml).toContain('--muni-accent: #6CF8BB');
    expect(darkHtml).toContain('"municipalGlyph":"#0B1626"');
  });
});
