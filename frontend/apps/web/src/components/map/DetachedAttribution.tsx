import { AttributionControl, type AttributionControlOptions } from 'maplibre-gl';
import { useEffect } from 'react';
import { useMap } from 'react-map-gl/maplibre';

/**
 * MapLibre's defaults for a map's own attribution control (`defaultAttributionControlOptions` in
 * maplibre-gl's src/ui/control/attribution_control.ts, not exported by 6.4.1). A control created
 * directly does not get them, so they are repeated here to keep MapLibre's own credit (#258 N1).
 */
const MAPLIBRE_ATTRIBUTION_DEFAULTS: AttributionControlOptions = {
  compact: true,
  customAttribution: '<a href="https://maplibre.org/" target="_blank">MapLibre</a>',
};

/**
 * MapLibre's own attribution control (the style's MapTiler and OpenStreetMap credits, kept up to date by
 * MapLibre) rendered into an element outside the map canvas. /map uses it so its results sidebar and
 * bottom sheet, which cover every corner of the canvas, cannot hide the credits (Asana 1219145771874977).
 *
 * It keeps MapLibre's default options and only stays expanded: the panels have room for the one-line
 * credits, so they need no toggle.
 */
export function DetachedAttribution({ target }: { target: HTMLElement | null }) {
  const { current: mapRef } = useMap();

  useEffect(() => {
    const map = mapRef?.getMap();
    if (!map || !target) return;
    const control = new AttributionControl({ ...MAPLIBRE_ATTRIBUTION_DEFAULTS, compact: false });
    target.appendChild(control.onAdd(map));
    return () => control.onRemove();
  }, [mapRef, target]);

  return null;
}
