// MapLibre GL ships the styles for its canvas, controls, popups and attribution
// as a stylesheet. Importing this module once from any map component pulls the
// bundled CSS into the build (Vite handles the asset URL rewriting).
import { setWorkerUrl } from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';
// Vite: ?worker&url emits a self-contained worker chunk (required for MapLibre v6).
import workerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url';

setWorkerUrl(workerUrl);
