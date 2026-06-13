import 'leaflet/dist/leaflet.css';
import L from 'leaflet';
import markerIcon2x from 'leaflet/dist/images/marker-icon-2x.png';
import markerIcon from 'leaflet/dist/images/marker-icon.png';
import markerShadow from 'leaflet/dist/images/marker-shadow.png';

/**
 * Leaflet derives its default marker icon paths relative to the CSS, which the
 * bundler rewrites — so the icons 404 in a Vite build. Re-point them at the
 * bundled asset URLs. Importing this module once (any map component) is enough.
 */
type IconDefaultPrototype = { _getIconUrl?: unknown };
delete (L.Icon.Default.prototype as unknown as IconDefaultPrototype)._getIconUrl;

L.Icon.Default.mergeOptions({
  iconRetinaUrl: markerIcon2x,
  iconUrl: markerIcon,
  shadowUrl: markerShadow,
});
