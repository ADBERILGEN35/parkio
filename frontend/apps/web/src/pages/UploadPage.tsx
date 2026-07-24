import { zodResolver } from '@hookform/resolvers/zod';
import {
  PARKING_CONTEXTS,
  LEGAL_STATUSES,
  SPOT_VEHICLE_TYPES,
  VIOLATION_REASONS,
  type LegalStatus,
  type Spot,
  type SpotVehicleType,
  type UploadMediaResponse,
} from '@parkio/types';
import { createIdempotencyKey } from '@parkio/api-client';
import {
  Button,
  Icon,
  Input,
  LoadingState,
  SoftBadge,
  StatusBadge,
  Surface,
  cn,
  type BadgeTone,
} from '@parkio/ui';
import {
  createSpotFormSchema,
  mediaUploadSchema,
  type CreateSpotFormValues,
} from '@parkio/validation';
import {
  useEffect,
  useRef,
  useState,
  type ChangeEvent,
  type DragEvent,
  type ReactNode,
} from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useForm, type UseFormRegisterReturn } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router-dom';
import { useParkioSdk } from '@/app/AppRuntimeContext';
import { ConfirmDialog } from '@/components/ConfirmDialog';
import { FriendlyApiErrorMessage } from '@/components/FriendlyApiErrorMessage';
import { MapPicker } from '@/components/map/MapPicker';
import { isValidLatLng } from '@/components/map/mapConfig';
import { PlaceSearch } from '@/components/map/PlaceSearch';
import { useUnsavedChangesGuard } from '@/hooks/useUnsavedChangesGuard';
import { enumLabel } from '@/lib/format';
import { spotStatusLabel } from '@/lib/localized-status';
import { invalidateGamificationQueries } from '@/lib/gamificationCache';
import { type GeocodeResult } from '@/lib/geocoding';
import { isUploadWizardDirty } from '@/lib/uploadDirty';
import { showError, showSuccess } from '@/lib/toast';
import { parkingKeys } from '@/data/keys';

type SubmitPhase = 'idle' | 'uploading' | 'creating';

const ACCEPTED_TYPES = 'image/jpeg,image/png,image/webp';

/** Wizard steps. Success is a terminal state, not one of the four numbered steps. */
const STEP_PHOTO = 0;
const STEP_LOCATION = 1;
const STEP_DETAILS = 2;
const STEP_REVIEW = 3;
const TOTAL_STEPS = 4;

const STEP_DESC_KEYS = [
  'upload.stepPhotoDesc',
  'upload.stepLocationDesc',
  'upload.stepDetailsDesc',
  'upload.stepReviewDesc',
] as const;

const STEP_PANEL_TITLE_KEYS = [
  'upload.panelPhoto',
  'upload.panelLocation',
  'upload.panelDetails',
  'upload.panelReview',
] as const;

const VEHICLE_ICONS: Record<SpotVehicleType, string> = {
  SEDAN: 'directions_car',
  HATCHBACK: 'directions_car',
  SUV: 'directions_car',
  VAN: 'airport_shuttle',
  MOTORCYCLE: 'two_wheeler',
  ANY: 'done_all',
};

const LEGAL_STATUS_META: Record<
  LegalStatus,
  { tone: BadgeTone; icon: string; descriptionKey: string }
> = {
  LEGAL: {
    tone: 'success',
    icon: 'check_circle',
    descriptionKey: 'upload.legalAllowed',
  },
  UNCERTAIN: {
    tone: 'warning',
    icon: 'help',
    descriptionKey: 'upload.legalUncertain',
  },
  ILLEGAL_OR_RISKY: {
    tone: 'danger',
    icon: 'gpp_bad',
    descriptionKey: 'upload.legalRisky',
  },
};

/** `1536000` → `1.5 MB`. */
function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  const kb = bytes / 1024;
  if (kb < 1024) return `${kb.toFixed(1)} KB`;
  return `${(kb / 1024).toFixed(1)} MB`;
}

/**
 * Upload & Create Spot wizard (`/upload`). A four-step Stitch-style flow —
 * Photo → Location → Details → Review → (Success) — over the unchanged create
 * pipeline: validate file → upload media (reused on retry, cleared when the file
 * changes) → create spot with separate idempotency keys → confirm → redirect.
 * The backend supports a single photo and a single create request; no
 * multi-photo, price, amenities or geocoding are introduced.
 */
export function UploadPage() {
  const { mediaApi, parkingApi } = useParkioSdk();
  const { t } = useTranslation('media');
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const fileInputRef = useRef<HTMLInputElement>(null);

  const [step, setStep] = useState(STEP_PHOTO);
  const [file, setFile] = useState<File | null>(null);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [isDragging, setIsDragging] = useState(false);
  const [fileError, setFileError] = useState<string | null>(null);
  // Kept after a successful upload so a failed create-spot can be retried
  // without re-uploading; cleared when the user picks a different file.
  const [uploadedMedia, setUploadedMedia] = useState<UploadMediaResponse | null>(null);
  const [phase, setPhase] = useState<SubmitPhase>('idle');
  const [submitError, setSubmitError] = useState<unknown>(null);
  const [createdSpot, setCreatedSpot] = useState<Spot | null>(null);
  // Human-readable label for the current pin source (place name or map point).
  const [locationLabel, setLocationLabel] = useState<string | null>(null);

  const {
    register,
    handleSubmit,
    watch,
    trigger,
    setValue,
    formState: { errors, isDirty: formIsDirty },
  } = useForm<CreateSpotFormValues>({
    resolver: zodResolver(createSpotFormSchema),
    defaultValues: {
      addressText: '',
      description: '',
      manualLocationEdited: false,
      suitableVehicleTypes: [],
      violationReasons: [],
    },
  });

  const values = watch();
  const legalStatus = values.legalStatus;

  const wizardDirty = isUploadWizardDirty({
    hasSucceeded: createdSpot !== null,
    hasSelectedFile: file !== null,
    hasUploadedMedia: uploadedMedia !== null,
    formIsDirty,
    hasLocationLabel: locationLabel !== null,
  });
  const blocker = useUnsavedChangesGuard({ when: wizardDirty });

  // Editing a coordinate field or clicking the map both count as a manual edit.
  const markManual = () => setValue('manualLocationEdited', true, { shouldDirty: true });

  const latValue = Number(values.latitude);
  const lngValue = Number(values.longitude);
  const hasCoords = isValidLatLng(latValue, lngValue);
  const pickerLat = hasCoords ? latValue : null;
  const pickerLng = hasCoords ? lngValue : null;

  const handlePick = (lat: number, lng: number) => {
    setValue('latitude', Number(lat.toFixed(6)), { shouldValidate: true, shouldDirty: true });
    setValue('longitude', Number(lng.toFixed(6)), { shouldValidate: true, shouldDirty: true });
    setValue('manualLocationEdited', true, { shouldDirty: true });
    setLocationLabel(t('upload.selectedMapPoint'));
  };

  /**
   * Geocoded place chosen from the typeahead. Sets coordinates + marks a manual
   * edit, and fills the optional address only when empty (no reverse geocoding —
   * we never overwrite what the user typed). Does NOT submit/create anything.
   */
  const handleSelectPlace = (result: GeocodeResult) => {
    setValue('latitude', Number(result.lat.toFixed(6)), { shouldValidate: true, shouldDirty: true });
    setValue('longitude', Number(result.lng.toFixed(6)), { shouldValidate: true, shouldDirty: true });
    setValue('manualLocationEdited', true, { shouldDirty: true });
    if (!values.addressText || values.addressText.trim() === '') {
      // addressText is capped at 512 chars by the schema.
      setValue('addressText', result.displayName.slice(0, 512), {
        shouldValidate: true,
        shouldDirty: true,
      });
    }
    setLocationLabel(result.secondary || result.primary);
  };

  // Object-URL preview for the chosen file (guarded — jsdom lacks createObjectURL).
  useEffect(() => {
    if (!file) {
      setPreviewUrl(null);
      return;
    }
    let url: string | null = null;
    try {
      url = URL.createObjectURL(file);
    } catch {
      url = null;
    }
    setPreviewUrl(url);
    return () => {
      if (url) {
        try {
          URL.revokeObjectURL(url);
        } catch {
          /* ignore */
        }
      }
    };
  }, [file]);

  // Show the success confirmation briefly, then redirect to the created spot.
  useEffect(() => {
    if (!createdSpot) return;
    const timer = setTimeout(() => navigate(`/spots/${createdSpot.id}`), 1500);
    return () => clearTimeout(timer);
  }, [createdSpot, navigate]);

  const acceptFile = (selected: File | null) => {
    setFile(selected);
    // A new file invalidates any previously uploaded media (forces a re-upload).
    setUploadedMedia(null);
    setSubmitError(null);
    setFileError(null);
    if (selected) {
      const check = mediaUploadSchema.safeParse({ file: selected });
      if (!check.success) {
        setFileError(check.error.issues[0]?.message ?? t('upload.invalidFile'));
      }
    }
  };

  const onFileChange = (event: ChangeEvent<HTMLInputElement>) => {
    acceptFile(event.target.files?.[0] ?? null);
  };

  const onDrop = (event: DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    setIsDragging(false);
    if (phase !== 'idle') return;
    acceptFile(event.dataTransfer.files?.[0] ?? null);
  };

  const clearFile = () => {
    acceptFile(null);
    if (fileInputRef.current) fileInputRef.current.value = '';
  };

  /** Validates the current step; returns true when it is safe to advance. */
  const validateStep = async (current: number): Promise<boolean> => {
    if (current === STEP_PHOTO) {
      if (!file && !uploadedMedia) {
        setFileError(t('upload.choosePhoto'));
        return false;
      }
      if (file) {
        const check = mediaUploadSchema.safeParse({ file });
        if (!check.success) {
          setFileError(check.error.issues[0]?.message ?? t('upload.invalidFile'));
          return false;
        }
      }
      return true;
    }
    if (current === STEP_LOCATION) {
      return trigger(['latitude', 'longitude', 'addressText']);
    }
    if (current === STEP_DETAILS) {
      // Full validation here also covers the illegal/risky → violation-reasons
      // refinement (a schema-level superRefine) before reaching Review.
      return trigger();
    }
    return true;
  };

  const goNext = async () => {
    if (await validateStep(step)) {
      setStep((s) => Math.min(s + 1, STEP_REVIEW));
    }
  };

  const goBack = () => setStep((s) => Math.max(s - 1, STEP_PHOTO));

  const submitCreate = handleSubmit(async (formValues) => {
    setSubmitError(null);

    if (!uploadedMedia) {
      if (!file) {
        setFileError(t('upload.choosePhoto'));
        setStep(STEP_PHOTO);
        return;
      }
      const check = mediaUploadSchema.safeParse({ file });
      if (!check.success) {
        const message = check.error.issues[0]?.message ?? t('upload.invalidFile');
        setFileError(message);
        showError(message);
        setStep(STEP_PHOTO);
        return;
      }
    }

    try {
      let media = uploadedMedia;
      if (!media) {
        setPhase('uploading');
        media = await mediaApi.uploadMedia(file as File, createIdempotencyKey());
        setUploadedMedia(media);
        showSuccess(t('upload.photoUploaded'));
      }

      setPhase('creating');
      const spot = await parkingApi.createParkingSpot(
        {
          mediaId: media.mediaId,
          latitude: formValues.latitude,
          longitude: formValues.longitude,
          addressText: formValues.addressText || undefined,
          description: formValues.description || undefined,
          manualLocationEdited: formValues.manualLocationEdited,
          suitableVehicleTypes: formValues.suitableVehicleTypes,
          parkingContext: formValues.parkingContext,
          legalStatus: formValues.legalStatus,
          violationReasons:
            formValues.violationReasons.length > 0 ? formValues.violationReasons : undefined,
        },
        createIdempotencyKey(),
      );
      setCreatedSpot(spot);
      // The new spot must show up in My Spots / Nearby, and the upload reward
      // (points/trust, async via events) must not leave stale derived views.
      await queryClient.invalidateQueries({ queryKey: parkingKeys.mySpots() });
      await queryClient.invalidateQueries({ queryKey: parkingKeys.nearbyRoot() });
      await invalidateGamificationQueries(queryClient);
      showSuccess(t('upload.spotCreated'));
    } catch (error) {
      setSubmitError(error);
      showError(t('upload.createErrorRetry'));
    } finally {
      setPhase('idle');
    }
  });

  const pending = phase !== 'idle';

  const leaveConfirmDialog = (
    <ConfirmDialog
      open={blocker.state === 'blocked'}
      title={t('upload.unsavedChanges.title')}
      description={t('upload.unsavedChanges.description')}
      cancelLabel={t('upload.unsavedChanges.stay')}
      confirmLabel={t('upload.unsavedChanges.leave')}
      onCancel={() => {
        if (blocker.state === 'blocked') blocker.reset();
      }}
      onConfirm={() => {
        if (blocker.state === 'blocked') blocker.proceed();
      }}
    />
  );

  if (createdSpot) {
    return (
      <>
        {leaveConfirmDialog}
        <WizardContainer>
          <SuccessPanel spot={createdSpot} />
        </WizardContainer>
      </>
    );
  }

  return (
    <>
      {leaveConfirmDialog}
    <WizardContainer>
      <WizardHeader step={step} />

      {/* Not a <form>: steps submit via explicit buttons, and the Location step
          embeds PlaceSearch's own <form> (nested forms are invalid HTML). */}
      <div>
        <fieldset disabled={pending} className="m-0 border-0 p-0">
          {/* Step 1 — Photo */}
          <StepPanel active={step === STEP_PHOTO} title={t(STEP_PANEL_TITLE_KEYS[0])} description={t(STEP_DESC_KEYS[0])}>
            <div className="flex flex-col gap-sm">
              <input
                ref={fileInputRef}
                type="file"
                accept={ACCEPTED_TYPES}
                aria-label={t("upload.spotPhotoAria")}
                className="sr-only"
                onChange={onFileChange}
              />
              {file ? (
                <div className="flex flex-col gap-sm rounded-2xl bg-surface-container-low p-md sm:flex-row sm:items-center">
                  <div className="h-28 w-full shrink-0 overflow-hidden rounded-xl bg-surface-container-high sm:w-40">
                    {previewUrl ? (
                      <img
                        src={previewUrl}
                        alt={t("upload.previewAlt")}
                        className="h-full w-full object-cover"
                      />
                    ) : (
                      <div className="flex h-full w-full items-center justify-center text-on-surface-variant">
                        <Icon name="image" className="text-[32px] leading-none" />
                      </div>
                    )}
                  </div>
                  <div className="min-w-0 flex-1">
                    <p className="m-0 truncate text-body-md font-semibold text-on-surface">
                      {file.name}
                    </p>
                    <p className="m-0 mt-xs text-label-sm text-on-surface-variant">
                      {formatFileSize(file.size)}
                    </p>
                    <div className="mt-sm flex flex-wrap items-center gap-sm">
                      {uploadedMedia ? (
                        <SoftBadge tone="success" icon="cloud_done">
                          {t('upload.uploadedReuse')}
                        </SoftBadge>
                      ) : !fileError ? (
                        <SoftBadge tone="primary" icon="check">
                          {t('upload.readyToUpload')}
                        </SoftBadge>
                      ) : null}
                      <Button
                        type="button"
                        variant="ghost"
                        onClick={() => fileInputRef.current?.click()}
                      >
                        <Icon name="autorenew" className="text-[16px] leading-none" />
                        {t('upload.replace')}
                      </Button>
                      <Button type="button" variant="ghost" onClick={clearFile}>
                        <Icon name="delete" className="text-[16px] leading-none" />
                        {t('upload.remove')}
                      </Button>
                    </div>
                  </div>
                </div>
              ) : (
                <div
                  role="button"
                  tabIndex={0}
                  aria-label={t("upload.dropzoneAria")}
                  onClick={() => fileInputRef.current?.click()}
                  onKeyDown={(event) => {
                    if (event.key === 'Enter' || event.key === ' ') {
                      event.preventDefault();
                      fileInputRef.current?.click();
                    }
                  }}
                  onDragOver={(event) => {
                    event.preventDefault();
                    setIsDragging(true);
                  }}
                  onDragLeave={() => setIsDragging(false)}
                  onDrop={onDrop}
                  className={cn(
                    'flex min-h-[220px] cursor-pointer flex-col items-center justify-center gap-sm rounded-2xl border-2 border-dashed p-xl text-center transition-colors duration-std',
                    isDragging
                      ? 'border-primary bg-primary/5'
                      : 'border-outline-variant bg-surface hover:bg-surface-container-low',
                  )}
                >
                  <span className="flex h-16 w-16 items-center justify-center rounded-full bg-surface-container-high">
                    <Icon name="add_a_photo" className="text-[32px] leading-none text-primary" />
                  </span>
                  <p className="m-0 text-body-md font-semibold text-on-surface">
                    {t('upload.dropzoneTitle')}
                  </p>
                  <p className="m-0 text-label-sm text-on-surface-variant">
                    {t('upload.dropzoneHint')}
                  </p>
                </div>
              )}

              <p className="m-0 flex items-center gap-xs text-label-sm text-on-surface-variant">
                <Icon name="lock" className="text-[14px] leading-none" />
                {t('upload.privacyNote')}
              </p>
              {fileError ? (
                <p className="m-0 flex items-center gap-xs text-label-sm text-error">
                  <Icon name="error" className="text-[14px] leading-none" />
                  {fileError}
                </p>
              ) : null}
            </div>
          </StepPanel>

          {/* Step 2 — Location */}
          <StepPanel
            active={step === STEP_LOCATION}
            title={t(STEP_PANEL_TITLE_KEYS[1])}
            description={t(STEP_DESC_KEYS[1])}
          >
            <div className="flex flex-col gap-md">
              <p className="m-0 text-label-sm text-on-surface-variant">
                {t('upload.locationHint')}
              </p>

              {/* Primary: address / place typeahead search */}
              <PlaceSearch onSelect={handleSelectPlace} />

              {locationLabel ? (
                <p className="m-0 flex items-center gap-xs text-label-sm font-medium text-on-surface">
                  <Icon name="location_on" className="text-[16px] leading-none text-primary" />
                  {t('upload.selectedLocation', { label: locationLabel })}
                </p>
              ) : null}

              <div className="overflow-hidden rounded-2xl shadow-soft ring-1 ring-outline-variant/20">
                <MapPicker latitude={pickerLat} longitude={pickerLng} onPick={handlePick} height={320} />
              </div>

              <div className="flex flex-wrap items-center gap-sm">
                <span className="text-label-sm font-medium text-on-surface-variant">
                  {t('upload.selectedCoordinates')}
                </span>
                {hasCoords ? (
                  <SoftBadge tone="primary" icon="my_location">
                    {latValue.toFixed(6)}, {lngValue.toFixed(6)}
                  </SoftBadge>
                ) : (
                  <SoftBadge tone="neutral" icon="location_searching">
                    {t('upload.notSetYet')}
                  </SoftBadge>
                )}
              </div>

              <Input
                label={t("upload.addressOptional")}
                error={errors.addressText?.message}
                {...register('addressText')}
              />

              {/* Advanced: manual coordinate fallback for testers/developers */}
              <details className="border-t border-outline-variant/30 pt-sm">
                <summary className="cursor-pointer list-none text-label-sm font-semibold text-on-surface-variant marker:content-none">
                  <span className="inline-flex items-center gap-xs">
                    <Icon name="tune" className="text-[16px] leading-none" />
                    {t('upload.advancedCoordinates')}
                  </span>
                </summary>
                <div className="mt-sm flex flex-col gap-md">
                  <div className="grid grid-cols-1 gap-md sm:grid-cols-2">
                    <Input
                      label={t("upload.latitude")}
                      inputMode="decimal"
                      error={errors.latitude?.message}
                      {...register('latitude', { onChange: markManual })}
                    />
                    <Input
                      label={t("upload.longitude")}
                      inputMode="decimal"
                      error={errors.longitude?.message}
                      {...register('longitude', { onChange: markManual })}
                    />
                  </div>
                  <label className="flex items-center gap-sm text-body-md text-on-surface">
                    <input
                      type="checkbox"
                      className="h-4 w-4 rounded border-outline-variant text-primary focus:ring-primary"
                      {...register('manualLocationEdited')}
                    />
                    {t('upload.adjustedManually')}
                  </label>
                </div>
              </details>
            </div>
          </StepPanel>

          {/* Step 3 — Details */}
          <StepPanel
            active={step === STEP_DETAILS}
            title={t(STEP_PANEL_TITLE_KEYS[2])}
            description={t(STEP_DESC_KEYS[2])}
          >
            <div className="flex flex-col gap-lg">
              <TextAreaField
                label={t("upload.descriptionOptional")}
                placeholder={t("upload.descriptionPlaceholder")}
                error={errors.description?.message}
                registration={register('description')}
              />

              <FieldGroup
                label={t("upload.vehicleTypes")}
                hint={t("upload.vehicleTypesHint")}
                error={errors.suitableVehicleTypes?.message}
              >
                <div className="grid grid-cols-2 gap-sm sm:grid-cols-3">
                  {SPOT_VEHICLE_TYPES.map((type) => (
                    <SelectionCard
                      key={type}
                      type="checkbox"
                      value={type}
                      icon={VEHICLE_ICONS[type]}
                      label={enumLabel(type, t)}
                      registration={register('suitableVehicleTypes')}
                    />
                  ))}
                </div>
              </FieldGroup>

              <SelectField
                label={t("upload.parkingContext")}
                error={errors.parkingContext?.message}
                registration={register('parkingContext')}
                options={PARKING_CONTEXTS}
              />

              <FieldGroup
                label={t("upload.legalStatus")}
                hint={t("upload.legalStatusHint")}
                error={errors.legalStatus?.message}
              >
                <div className="grid grid-cols-1 gap-sm sm:grid-cols-3">
                  {LEGAL_STATUSES.map((status) => (
                    <SelectionCard
                      key={status}
                      type="radio"
                      value={status}
                      icon={LEGAL_STATUS_META[status].icon}
                      label={enumLabel(status, t, ['legalStatus'])}
                      description={t(LEGAL_STATUS_META[status].descriptionKey)}
                      tone={LEGAL_STATUS_META[status].tone}
                      registration={register('legalStatus')}
                    />
                  ))}
                </div>
              </FieldGroup>

              {legalStatus === 'ILLEGAL_OR_RISKY' ? (
                <>
                  <FieldGroup label={t("upload.violationReasons")} error={errors.violationReasons?.message}>
                    <div className="flex flex-wrap gap-sm">
                      {VIOLATION_REASONS.map((reason) => (
                        <SelectionChip
                          key={reason}
                          value={reason}
                          label={enumLabel(reason, t, ['violationReason'])}
                          registration={register('violationReasons')}
                        />
                      ))}
                    </div>
                  </FieldGroup>
                  <div className="flex items-start gap-sm rounded-2xl bg-error/10 p-md text-error">
                    <Icon name="gpp_bad" className="text-[18px] leading-none" />
                    <p className="m-0 text-body-md">
                      {t('upload.illegalRejected')}
                    </p>
                  </div>
                </>
              ) : null}
            </div>
          </StepPanel>

          {/* Step 4 — Review */}
          <StepPanel
            active={step === STEP_REVIEW}
            title={t(STEP_PANEL_TITLE_KEYS[3])}
            description={t(STEP_DESC_KEYS[3])}
          >
            <ReviewSummary
              previewUrl={previewUrl}
              fileName={file?.name ?? null}
              fileSize={file?.size ?? null}
              uploaded={uploadedMedia !== null}
              values={values}
              hasCoords={hasCoords}
              latValue={latValue}
              lngValue={lngValue}
            />

            {submitError !== null ? (
              <div className="mt-md flex flex-col gap-sm">
                <FriendlyApiErrorMessage error={submitError} />
                {uploadedMedia ? (
                  <p className="m-0 flex items-start gap-sm rounded-2xl bg-surface-container-low p-md text-body-md text-on-surface-variant">
                    <Icon name="cloud_done" className="text-[18px] leading-none text-secondary" />
                    {t('upload.retryHint')}
                  </p>
                ) : null}
              </div>
            ) : null}

            {pending ? (
              <div className="mt-md">
                <LoadingState label={phase === 'uploading' ? t('upload.uploadingPhoto') : t('upload.creatingSpot')} />
              </div>
            ) : null}
          </StepPanel>

          {/* Footer — Back / Next / Submit */}
          <div className="mt-lg flex items-center justify-between gap-sm">
            <Button
              type="button"
              variant="ghost"
              onClick={goBack}
              disabled={step === STEP_PHOTO || pending}
            >
              <Icon name="arrow_back" className="text-[16px] leading-none" />
              {t('upload.back')}
            </Button>

            {step < STEP_REVIEW ? (
              <Button type="button" onClick={() => void goNext()}>
                {t('upload.next')}
                <Icon name="arrow_forward" className="text-[16px] leading-none" />
              </Button>
            ) : (
              <Button type="button" onClick={() => void submitCreate()} disabled={pending}>
                {pending ? (
                  <Icon
                    name="progress_activity"
                    className="text-[16px] leading-none motion-safe:animate-spin"
                  />
                ) : null}
                {phase === 'uploading'
                  ? t('upload.uploading')
                  : phase === 'creating'
                    ? t('upload.submitting')
                    : t('upload.submit')}
              </Button>
            )}
          </div>
        </fieldset>
      </div>
    </WizardContainer>
    </>
  );
}

/* ------------------------------------------------------------------------- */
/* Wizard chrome                                                              */
/* ------------------------------------------------------------------------- */

function WizardContainer({ children }: { children: ReactNode }) {
  return (
    <div className="mx-auto w-full max-w-3xl min-w-0 px-md py-lg text-on-background md:px-xl">
      {children}
    </div>
  );
}

function WizardHeader({ step }: { step: number }) {
  const { t } = useTranslation('media');
  const progress = ((step + 1) / TOTAL_STEPS) * 100;
  const stepTitles = [
    t('upload.stepPhoto'),
    t('upload.stepLocation'),
    t('upload.stepDetails'),
    t('upload.stepReview'),
  ];
  return (
    <div className="mb-lg">
      <p className="m-0 flex items-center gap-xs text-label-md font-semibold uppercase tracking-wider text-primary">
        <Icon name="add_location_alt" className="text-[16px] leading-none" />
        {t('upload.title')}
      </p>
      <div className="mt-sm flex flex-wrap items-end justify-between gap-sm">
        <h1 className="m-0 text-headline-lg-mobile text-on-surface md:text-headline-lg">
          {stepTitles[step]}
        </h1>
        <span className="text-label-md font-semibold text-on-surface-variant">
          {t('upload.stepOf', { current: step + 1, total: TOTAL_STEPS })}
        </span>
      </div>
      <p className="m-0 mt-xs text-body-md text-on-surface-variant">{t('upload.subtitle')}</p>
      <div
        className="mt-md h-1.5 w-full overflow-hidden rounded-full bg-surface-container-high"
        role="progressbar"
        aria-valuenow={step + 1}
        aria-valuemin={1}
        aria-valuemax={TOTAL_STEPS}
        aria-label={t('upload.stepProgressAria', { current: step + 1, total: TOTAL_STEPS })}
      >
        <div
          className="h-full rounded-full bg-primary motion-safe:transition-[width] motion-safe:duration-fluid motion-safe:ease-out"
          style={{ width: `${progress}%` }}
        />
      </div>
    </div>
  );
}

function StepPanel({
  active,
  title,
  description,
  children,
}: {
  active: boolean;
  title: string;
  description: string;
  children: ReactNode;
}) {
  if (!active) return null;
  return (
    <Surface level="card" className="rounded-3xl p-lg motion-safe:animate-fade-in-up">
      <div className="mb-md">
        <h2 className="m-0 text-title-lg text-on-surface">{title}</h2>
        <p className="m-0 mt-xs text-body-md text-on-surface-variant">{description}</p>
      </div>
      {children}
    </Surface>
  );
}

/* ------------------------------------------------------------------------- */
/* Review summary                                                             */
/* ------------------------------------------------------------------------- */

function ReviewSummary({
  previewUrl,
  fileName,
  fileSize,
  uploaded,
  values,
  hasCoords,
  latValue,
  lngValue,
}: {
  previewUrl: string | null;
  fileName: string | null;
  fileSize: number | null;
  uploaded: boolean;
  values: CreateSpotFormValues;
  hasCoords: boolean;
  latValue: number;
  lngValue: number;
}) {
  const { t } = useTranslation('media');
  const description = (values.description ?? '').trim();
  const descriptionSnippet =
    description.length > 140 ? `${description.slice(0, 140)}…` : description;

  return (
    <div className="flex flex-col gap-md">
      {/* Photo preview */}
      <div className="flex flex-col gap-sm rounded-2xl bg-surface-container-low p-md sm:flex-row sm:items-center">
        <div className="h-28 w-full shrink-0 overflow-hidden rounded-xl bg-surface-container-high sm:w-40">
          {previewUrl ? (
            <img src={previewUrl} alt={t("upload.reviewPreviewAlt")} className="h-full w-full object-cover" />
          ) : (
            <div className="flex h-full w-full items-center justify-center text-on-surface-variant">
              <Icon name="image" className="text-[32px] leading-none" />
            </div>
          )}
        </div>
        <div className="min-w-0 flex-1">
          <p className="m-0 truncate text-body-md font-semibold text-on-surface">
            {fileName ?? t('upload.photoFallback')}
          </p>
          {fileSize !== null ? (
            <p className="m-0 mt-xs text-label-sm text-on-surface-variant">
              {formatFileSize(fileSize)}
            </p>
          ) : null}
          {uploaded ? (
            <div className="mt-sm">
              <SoftBadge tone="success" icon="cloud_done">
                {t('upload.reviewUploadedReuse')}
              </SoftBadge>
            </div>
          ) : null}
        </div>
      </div>

      <ReviewRow icon="my_location" label={t("upload.reviewCoordinates")}>
        {hasCoords ? `${latValue.toFixed(6)}, ${lngValue.toFixed(6)}` : t('upload.notSet')}
      </ReviewRow>

      {values.addressText && values.addressText.trim() !== '' ? (
        <ReviewRow icon="home_pin" label={t("upload.reviewAddress")}>
          {values.addressText}
        </ReviewRow>
      ) : null}

      {descriptionSnippet !== '' ? (
        <ReviewRow icon="description" label={t("upload.reviewDescription")}>
          {descriptionSnippet}
        </ReviewRow>
      ) : null}

      <ReviewRow icon="directions_car" label={t("upload.reviewVehicles")}>
        {values.suitableVehicleTypes.length > 0 ? (
          <span className="flex flex-wrap gap-xs">
            {values.suitableVehicleTypes.map((type) => (
              <SoftBadge key={type} tone="neutral">
                {enumLabel(type, t)}
              </SoftBadge>
            ))}
          </span>
        ) : (
          '—'
        )}
      </ReviewRow>

      <ReviewRow icon="local_parking" label={t("upload.parkingContext")}>
        {values.parkingContext ? enumLabel(values.parkingContext, t, ['parkingContext']) : '—'}
      </ReviewRow>

      <ReviewRow icon="gavel" label={t("upload.legalStatus")}>
        {values.legalStatus ? (
          <SoftBadge tone={LEGAL_STATUS_META[values.legalStatus].tone}>
            {enumLabel(values.legalStatus, t, ['legalStatus'])}
          </SoftBadge>
        ) : (
          '—'
        )}
      </ReviewRow>

      {values.violationReasons.length > 0 ? (
        <ReviewRow icon="warning" label={t("upload.reviewViolations")}>
          <span className="flex flex-wrap gap-xs">
            {values.violationReasons.map((reason) => (
              <SoftBadge key={reason} tone="danger" icon="warning">
                {enumLabel(reason, t, ['violationReason'])}
              </SoftBadge>
            ))}
          </span>
        </ReviewRow>
      ) : null}
    </div>
  );
}

function ReviewRow({
  icon,
  label,
  children,
}: {
  icon: string;
  label: string;
  children: ReactNode;
}) {
  return (
    <div className="flex items-start gap-md rounded-2xl bg-surface-container-low px-md py-sm">
      <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-surface-container-high">
        <Icon name={icon} className="text-[18px] leading-none text-primary" />
      </span>
      <div className="min-w-0 flex-1">
        <p className="m-0 text-label-sm uppercase tracking-wider text-on-surface-variant">{label}</p>
        <div className="mt-xs text-body-md text-on-surface">{children}</div>
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------------- */
/* Success                                                                    */
/* ------------------------------------------------------------------------- */

function SuccessPanel({ spot }: { spot: Spot }) {
  const { t } = useTranslation('media');
  return (
    <Surface level="raised" className="rounded-3xl p-lg">
      <div className="flex flex-col items-center gap-md py-lg text-center">
        <span className="flex h-20 w-20 items-center justify-center rounded-full bg-secondary-container text-on-secondary-container">
          <Icon name="check" className="text-[40px] leading-none" />
        </span>
        <h2 className="m-0 text-headline-md text-on-surface">{t('upload.successTitle')}</h2>
        <StatusBadge status={spot.status} label={spotStatusLabel(spot.status, t)} />
        <p className="m-0 max-w-sm text-body-md text-on-surface-variant">
          {t('upload.successRedirect')}
        </p>
        <Link
          to={`/spots/${spot.id}`}
          className="inline-flex items-center gap-sm rounded-full bg-primary px-lg py-sm text-label-md text-on-primary no-underline shadow-sm transition-all duration-std hover:bg-primary/90"
        >
          {t('upload.viewSpotNow')}
          <Icon name="arrow_forward" className="text-[16px] leading-none" />
        </Link>
      </div>
    </Surface>
  );
}

/* ------------------------------------------------------------------------- */
/* Field building blocks                                                      */
/* ------------------------------------------------------------------------- */

function FieldGroup({
  label,
  hint,
  error,
  children,
}: {
  label: string;
  hint?: string;
  error?: string;
  children: ReactNode;
}) {
  return (
    <div className="flex flex-col gap-xs">
      <span className="text-label-md font-semibold text-on-surface">{label}</span>
      {hint ? <span className="text-label-sm text-on-surface-variant">{hint}</span> : null}
      <div className="mt-xs">{children}</div>
      {error ? <span className="text-label-sm text-error">{error}</span> : null}
    </div>
  );
}

const TONE_SELECTED: Record<BadgeTone, string> = {
  primary: 'peer-checked:border-primary peer-checked:bg-primary-fixed peer-checked:text-on-primary-fixed',
  success:
    'peer-checked:border-secondary peer-checked:bg-secondary-container peer-checked:text-on-secondary-container',
  warning:
    'peer-checked:border-tertiary peer-checked:bg-tertiary-container/30 peer-checked:text-tertiary',
  danger: 'peer-checked:border-error peer-checked:bg-error/10 peer-checked:text-error',
  neutral: 'peer-checked:border-on-surface-variant peer-checked:bg-surface-container-high',
};

function SelectionCard({
  type,
  value,
  icon,
  label,
  description,
  tone = 'primary',
  registration,
}: {
  type: 'checkbox' | 'radio';
  value: string;
  icon: string;
  label: string;
  description?: string;
  tone?: BadgeTone;
  registration: UseFormRegisterReturn;
}) {
  return (
    <label className="cursor-pointer">
      <input type={type} value={value} className="peer sr-only" {...registration} />
      <span
        className={cn(
          'flex h-full flex-col gap-xs rounded-xl border border-outline-variant bg-surface p-md text-on-surface-variant transition-colors duration-std',
          'hover:border-primary/40 peer-focus-visible:ring-2 peer-focus-visible:ring-primary',
          TONE_SELECTED[tone],
        )}
      >
        <span className="flex items-center gap-sm">
          <Icon name={icon} className="text-[18px] leading-none" />
          <span className="text-body-md font-semibold">{label}</span>
        </span>
        {description ? <span className="text-label-sm">{description}</span> : null}
      </span>
    </label>
  );
}

function SelectionChip({
  value,
  label,
  registration,
}: {
  value: string;
  label: string;
  registration: UseFormRegisterReturn;
}) {
  return (
    <label className="cursor-pointer">
      <input type="checkbox" value={value} className="peer sr-only" {...registration} />
      <span
        className={cn(
          'inline-flex items-center gap-xs rounded-full border border-outline-variant bg-surface px-md py-sm text-label-md text-on-surface-variant transition-colors duration-std',
          'hover:border-primary/40 peer-focus-visible:ring-2 peer-focus-visible:ring-primary',
          'peer-checked:border-error peer-checked:bg-error/10 peer-checked:text-error',
        )}
      >
        {label}
      </span>
    </label>
  );
}

function SelectField({
  label,
  error,
  registration,
  options,
}: {
  label: string;
  error?: string;
  registration: UseFormRegisterReturn;
  options: readonly string[];
}) {
  const { t } = useTranslation('media');
  return (
    <label className="flex flex-col gap-xs">
      <span className="text-label-md font-semibold text-on-surface">{label}</span>
      <select
        className={cn(
          'w-full rounded-lg border-0 bg-surface px-md py-sm text-body-md text-on-surface shadow-sm',
          'focus:outline-none focus:ring-2',
          error ? 'ring-1 ring-error focus:ring-error' : 'ring-1 ring-outline-variant/40 focus:ring-primary',
        )}
        {...registration}
      >
        <option value="">{t("upload.selectOption")}</option>
        {options.map((option) => (
          <option key={option} value={option}>
            {enumLabel(option, t, ['parkingContext'])}
          </option>
        ))}
      </select>
      {error ? <span className="text-label-sm text-error">{error}</span> : null}
    </label>
  );
}

function TextAreaField({
  label,
  placeholder,
  error,
  registration,
}: {
  label: string;
  placeholder?: string;
  error?: string;
  registration: UseFormRegisterReturn;
}) {
  return (
    <label className="flex flex-col gap-xs">
      <span className="text-label-md font-semibold text-on-surface">{label}</span>
      <textarea
        rows={3}
        placeholder={placeholder}
        className={cn(
          'w-full rounded-lg border-0 bg-surface px-md py-sm text-body-md text-on-surface shadow-sm',
          'placeholder:text-outline focus:outline-none focus:ring-2',
          error ? 'ring-1 ring-error focus:ring-error' : 'ring-1 ring-outline-variant/40 focus:ring-primary',
        )}
        {...registration}
      />
      {error ? <span className="text-label-sm text-error">{error}</span> : null}
    </label>
  );
}
