import { useShareDraftStore } from '../shareDraftStore';

describe('shareDraftStore', () => {
  beforeEach(() => {
    useShareDraftStore.getState().reset();
  });

  it('vehicle toggling keeps ANY exclusive', () => {
    const store = useShareDraftStore.getState();
    store.toggleVehicleType('SEDAN');
    store.toggleVehicleType('SUV');
    expect(useShareDraftStore.getState().vehicleTypes).toEqual(['SEDAN', 'SUV']);

    store.toggleVehicleType('ANY');
    expect(useShareDraftStore.getState().vehicleTypes).toEqual(['ANY']);

    store.toggleVehicleType('HATCHBACK');
    expect(useShareDraftStore.getState().vehicleTypes).toEqual(['HATCHBACK']);
  });

  it('replacing the photo resets the upload pipeline state', () => {
    const store = useShareDraftStore.getState();
    store.setPhoto({ uri: 'file://a.jpg', width: 100, height: 100 });
    store.setMediaId('media-1');
    store.setUpload('ready', 1);

    store.setPhoto({ uri: 'file://b.jpg', width: 100, height: 100 });
    const state = useShareDraftStore.getState();
    expect(state.mediaId).toBeNull();
    expect(state.uploadPhase).toBe('idle');
    expect(state.uploadProgress).toBe(0);
  });

  it('manual location edits are sticky', () => {
    const store = useShareDraftStore.getState();
    store.setLocation({ latitude: 38.4, longitude: 27.1 });
    expect(useShareDraftStore.getState().manualLocationEdited).toBe(false);

    store.setLocation({ latitude: 38.5, longitude: 27.2 }, { manual: true });
    expect(useShareDraftStore.getState().manualLocationEdited).toBe(true);

    // A later automatic fix must not clear the manual flag.
    store.setLocation({ latitude: 38.6, longitude: 27.3 });
    expect(useShareDraftStore.getState().manualLocationEdited).toBe(true);
  });

  it('reset returns to the empty draft', () => {
    const store = useShareDraftStore.getState();
    store.setDescription('test');
    store.setLegalStatus('LEGAL');
    store.reset();
    const state = useShareDraftStore.getState();
    expect(state.description).toBe('');
    expect(state.legalStatus).toBeNull();
    expect(state.step).toBe('photo');
  });
});
