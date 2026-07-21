import { File, Paths } from 'expo-file-system';
import { removeJson, writeJson } from '@/services/jsonStore';
import { useShareDraftStore } from '../shareDraftStore';

describe('shareDraftStore', () => {
  beforeEach(async () => {
    useShareDraftStore.getState().reset();
    await removeJson('share-draft');
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

    store.setLocation({ latitude: 38.6, longitude: 27.3 });
    expect(useShareDraftStore.getState().manualLocationEdited).toBe(true);
  });

  it('reset returns to the empty draft and clears in-memory resume state', () => {
    const store = useShareDraftStore.getState();
    store.setDescription('test');
    store.setLegalStatus('LEGAL');
    store.setPhoto({ uri: 'file://keep.jpg', width: 10, height: 10 });
    useShareDraftStore.setState({ resumableDraft: true });
    store.reset();
    const state = useShareDraftStore.getState();
    expect(state.description).toBe('');
    expect(state.legalStatus).toBeNull();
    expect(state.photo).toBeNull();
    expect(state.step).toBe('photo');
    expect(state.resumableDraft).toBe(false);
  });

  it('hydrate restores a valid persisted draft after restart', async () => {
    await writeJson('share-draft', {
      step: 'details',
      photo: null,
      mediaId: null,
      location: { latitude: 38.4, longitude: 27.1 },
      gpsAccuracy: null,
      manualLocationEdited: false,
      addressText: 'Alsancak',
      description: 'corner spot',
      vehicleTypes: ['SEDAN'],
      parkingContext: 'STREET_PARKING',
      legalStatus: 'LEGAL',
      violationReasons: [],
      savedAt: new Date().toISOString(),
    });

    useShareDraftStore.setState({ hydrated: false, resumableDraft: false });
    await useShareDraftStore.getState().hydrate();

    const state = useShareDraftStore.getState();
    expect(state.hydrated).toBe(true);
    expect(state.resumableDraft).toBe(true);
    expect(state.step).toBe('details');
    expect(state.description).toBe('corner spot');
    expect(state.addressText).toBe('Alsancak');
  });

  it('hydrate clears a missing draft photo without crashing', async () => {
    await writeJson('share-draft', {
      step: 'location',
      photo: { uri: 'file://missing-draft.jpg', width: 100, height: 80 },
      mediaId: 'media-gone',
      location: null,
      gpsAccuracy: null,
      manualLocationEdited: false,
      addressText: '',
      description: 'still text',
      vehicleTypes: [],
      parkingContext: 'STREET_PARKING',
      legalStatus: null,
      violationReasons: [],
      savedAt: new Date().toISOString(),
    });

    useShareDraftStore.setState({ hydrated: false });
    await useShareDraftStore.getState().hydrate();

    const state = useShareDraftStore.getState();
    expect(state.photo).toBeNull();
    expect(state.mediaId).toBeNull();
    expect(state.step).toBe('photo');
    expect(state.description).toBe('still text');
    expect(state.resumableDraft).toBe(true);
  });

  it('reset deletes the durable draft photo file when present', () => {
    const durable = new File(Paths.document, 'parkio-store', 'draft-photo.jpg');
    durable.write('jpeg-bytes');
    expect(durable.exists).toBe(true);

    useShareDraftStore.getState().reset();
    expect(durable.exists).toBe(false);
  });
});
