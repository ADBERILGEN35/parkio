import '@/components/map/maplibreSetup';
import type { PublicExploreFacility } from '@parkio/types';
import { Icon, cn } from '@parkio/ui';
import Map, { Marker } from 'react-map-gl/maplibre';
import { getMapStyle } from '@/components/map/mapConfig';

export function PublicExploreMap({
  facilities,
  selectedId,
  onSelect,
}: {
  facilities: readonly PublicExploreFacility[];
  selectedId: string | null;
  onSelect: (id: string | null) => void;
}) {
  return (
    <div className="h-[420px] w-full overflow-hidden rounded-3xl border border-outline-variant/30">
      <Map
        initialViewState={{ latitude: 38.4237, longitude: 27.1428, zoom: 13 }}
        mapStyle={getMapStyle()}
        dragRotate={false}
        pitchWithRotate={false}
        onClick={() => onSelect(null)}
        style={{ height: '100%', width: '100%' }}
        aria-label="Read-only public parking map centered on Konak, Izmir"
      >
        {facilities.map((facility) => {
          const selected = facility.id === selectedId;
          const label = facility.displayName || facility.addressText || 'Municipal parking facility';
          return (
            <Marker
              key={facility.id}
              latitude={facility.latitude}
              longitude={facility.longitude}
              anchor="center"
            >
              <button
                type="button"
                aria-label={label}
                aria-pressed={selected}
                data-testid="public-explore-marker"
                onClick={(event) => {
                  event.stopPropagation();
                  onSelect(facility.id);
                }}
                className={cn(
                  'flex h-10 w-10 items-center justify-center rounded-xl border-2 border-white bg-secondary text-on-secondary shadow-lg focus:outline-none focus-visible:ring-4 focus-visible:ring-secondary/30',
                  selected && 'scale-110 ring-4 ring-secondary/25',
                )}
              >
                <Icon name="local_parking" filled className="text-[18px] leading-none" />
              </button>
            </Marker>
          );
        })}
      </Map>
    </div>
  );
}
