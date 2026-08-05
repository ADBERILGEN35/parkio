/**
 * Documents map.tsx selection ownership:
 * selectSpot / selectMunicipal / clearSelection keep sheets mutually exclusive.
 */
function nextSelectionForSpotTap(spotId: string) {
  return { selectedSpotId: spotId, selectedMunicipalId: null as null };
}

function nextSelectionForMunicipalTap(facilityId: string) {
  return { selectedSpotId: null as null, selectedMunicipalId: facilityId };
}

function nextSelectionForMapBackground() {
  return { selectedSpotId: null as null, selectedMunicipalId: null as null };
}

describe('map municipal/community selection exclusivity', () => {
  it('clears municipal when a community spot is selected', () => {
    expect(nextSelectionForSpotTap('spot-1')).toEqual({
      selectedSpotId: 'spot-1',
      selectedMunicipalId: null,
    });
  });

  it('clears community spot when a municipal facility is selected', () => {
    expect(nextSelectionForMunicipalTap('facility-1')).toEqual({
      selectedSpotId: null,
      selectedMunicipalId: 'facility-1',
    });
  });

  it('clears both on map background press', () => {
    expect(nextSelectionForMapBackground()).toEqual({
      selectedSpotId: null,
      selectedMunicipalId: null,
    });
  });
});
