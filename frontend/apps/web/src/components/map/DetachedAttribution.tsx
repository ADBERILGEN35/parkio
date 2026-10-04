import { AttributionControl } from 'maplibre-gl';
import { useEffect } from 'react';
import { useMap } from 'react-map-gl/maplibre';

/**
 * MapLibre's own attribution control (the style's MapTiler and OpenStreetMap credits, kept up to date by
 * MapLibre) rendered into an element outside the map canvas. /map uses it so its results sidebar and
 * bottom sheet, which cover every corner of the canvas, cannot hide the credits (Asana 1219145771874977).
 *
 * The control stays expanded: the panels have room for the one-line credits, so they need no toggle.
 */
export function DetachedAttribution({ target }: { target: HTMLElement | null }) {
  const { current: mapRef } = useMap();

  useEffect(() => {
    const map = mapRef?.getMap();
    if (!map || !target) return;
    const control = new AttributionControl({ compact: false });
    target.appendChild(control.onAdd(map));
    return () => control.onRemove();
  }, [mapRef, target]);

  return null;
}
