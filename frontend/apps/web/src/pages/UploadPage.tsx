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
import { useForm, type UseFormRegisterReturn } from 'react-hook-form';
import { Link, useNavigate } from 'react-router-dom';
import { mediaApi, parkingApi } from '@/api';
import { FriendlyApiErrorMessage } from '@/components/FriendlyApiErrorMessage';
import { MapPicker } from '@/components/map/MapPicker';
import { isValidLatLng } from '@/components/map/mapConfig';
import { PlaceSearch } from '@/components/map/PlaceSearch';
import { humanizeEnum } from '@/lib/format';
import { type GeocodeResult } from '@/lib/geocoding';

type SubmitPhase = 'idle' | 'uploading' | 'creating';

const ACCEPTED_TYPES = 'image/jpeg,image/png,image/webp';

/** Wizard steps. Success is a terminal state, not one of the four numbered steps. */
const STEP_PHOTO = 0;
const STEP_LOCATION = 1;
const STEP_DETAILS = 2;
const STEP_REVIEW = 3;
const TOTAL_STEPS = 4;

const STEP_META: { title: string; description: string }[] = [
  { title: 'Photo', description: 'Add a clear photo of the parking spot.' },
  { title: 'Location', description: 'Place the spot on the map or enter coordinates.' },
  { title: 'Details', description: 'Describe the spot and who it suits.' },
  { title: 'Review', description: 'Check everything, then publish.' },
];

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
  { tone: BadgeTone; icon: string; description: string }
> = {
  LEGAL: {
    tone: 'success',
    icon: 'check_circle',
    description: 'Parking here is allowed.',
  },
  UNCERTAIN: {
    tone: 'warning',
    icon: 'help',
    description: 'Rules are unclear or vary by time.',
  },
  ILLEGAL_OR_RISKY: {
    tone: 'danger',
    icon: 'gpp_bad',
    description: 'Parking here may be prohibited or risky.',
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
  const navigate = useNavigate();
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
    formState: { errors },
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

  // Editing a coordinate field or clicking the map both count as a manual edit.
  const markManual = () => setValue('manualLocationEdited', true);

  const latValue = Number(values.latitude);
  const lngValue = Number(values.longitude);
  const hasCoords = isValidLatLng(latValue, lngValue);
  const pickerLat = hasCoords ? latValue : null;
  const pickerLng = hasCoords ? lngValue : null;

  const handlePick = (lat: number, lng: number) => {
    setValue('latitude', Number(lat.toFixed(6)), { shouldValidate: true });
    setValue('longitude', Number(lng.toFixed(6)), { shouldValidate: true });
    setValue('manualLocationEdited', true);
    setLocationLabel('Selected map point');
  };

  /**
   * Geocoded place chosen from the typeahead. Sets coordinates + marks a manual
   * edit, and fills the optional address only when empty (no reverse geocoding —
   * we never overwrite what the user typed). Does NOT submit/create anything.
   */
  const handleSelectPlace = (result: GeocodeResult) => {
    setValue('latitude', Number(result.lat.toFixed(6)), { shouldValidate: true });
    setValue('longitude', Number(result.lng.toFixed(6)), { shouldValidate: true });
    setValue('manualLocationEdited', true);
    if (!values.addressText || values.addressText.trim() === '') {
      // addressText is capped at 512 chars by the schema.
      setValue('addressText', result.displayName.slice(0, 512), { shouldValidate: true });
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
        setFileError(check.error.issues[0]?.message ?? 'Invalid file');
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
        setFileError('Choose a photo to upload');
        return false;
      }
      if (file) {
        const check = mediaUploadSchema.safeParse({ file });
        if (!check.success) {
          setFileError(check.error.issues[0]?.message ?? 'Invalid file');
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
        setFileError('Choose a photo to upload');
        setStep(STEP_PHOTO);
        return;
      }
      const check = mediaUploadSchema.safeParse({ file });
      if (!check.success) {
        setFileError(check.error.issues[0]?.message ?? 'Invalid file');
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
    } catch (error) {
      setSubmitError(error);
    } finally {
      setPhase('idle');
    }
  });

  const pending = phase !== 'idle';

  if (createdSpot) {
    return (
      <WizardContainer>
        <SuccessPanel spot={createdSpot} />
      </WizardContainer>
    );
  }

  return (
    <WizardContainer>
      <WizardHeader step={step} />

      {/* Not a <form>: steps submit via explicit buttons, and the Location step
          embeds PlaceSearch's own <form> (nested forms are invalid HTML). */}
      <div>
        <fieldset disabled={pending} className="m-0 border-0 p-0">
          {/* Step 1 — Photo */}
          <StepPanel active={step === STEP_PHOTO} title="1. Photo" description={STEP_META[0].description}>
            <div className="flex flex-col gap-sm">
              <input
                ref={fileInputRef}
                type="file"
                accept={ACCEPTED_TYPES}
                aria-label="Spot photo"
                className="sr-only"
                onChange={onFileChange}
              />
              {file ? (
                <div className="flex flex-col gap-sm rounded-2xl bg-surface-container-low p-md sm:flex-row sm:items-center">
                  <div className="h-28 w-full shrink-0 overflow-hidden rounded-xl bg-surface-container-high sm:w-40">
                    {previewUrl ? (
                      <img
                        src={previewUrl}
                        alt="Selected spot preview"
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
                          Uploaded — reused on retry
                        </SoftBadge>
                      ) : !fileError ? (
                        <SoftBadge tone="primary" icon="check">
                          Ready to upload
                        </SoftBadge>
                      ) : null}
                      <Button
                        type="button"
                        variant="ghost"
                        onClick={() => fileInputRef.current?.click()}
                      >
                        <Icon name="autorenew" className="text-[16px] leading-none" />
                        Replace
                      </Button>
                      <Button type="button" variant="ghost" onClick={clearFile}>
                        <Icon name="delete" className="text-[16px] leading-none" />
                        Remove
                      </Button>
                    </div>
                  </div>
                </div>
              ) : (
                <div
                  role="button"
                  tabIndex={0}
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
                    Drag &amp; drop a photo, or click to select
                  </p>
                  <p className="m-0 text-label-sm text-on-surface-variant">
                    JPEG, PNG or WebP · up to 10 MB
                  </p>
                </div>
              )}

              <p className="m-0 text-label-sm text-on-surface-variant">
                The photo&apos;s signed viewing URL is generated later, on the spot detail page.
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
            title="2. Location"
            description={STEP_META[1].description}
          >
            <div className="flex flex-col gap-md">
              <p className="m-0 text-label-sm text-on-surface-variant">
                Search for an address or place, then fine-tune the pin by clicking the map. No
                reverse geocoding — clicking the map updates coordinates only.
              </p>

              {/* Primary: address / place typeahead search */}
              <PlaceSearch onSelect={handleSelectPlace} />

              {locationLabel ? (
                <p className="m-0 flex items-center gap-xs text-label-sm font-medium text-on-surface">
                  <Icon name="location_on" className="text-[16px] leading-none text-primary" />
                  Selected location: {locationLabel}
                </p>
              ) : null}

              <div className="overflow-hidden rounded-2xl shadow-soft ring-1 ring-outline-variant/20">
                <MapPicker latitude={pickerLat} longitude={pickerLng} onPick={handlePick} height={320} />
              </div>

              <div className="flex flex-wrap items-center gap-sm">
                <span className="text-label-sm font-medium text-on-surface-variant">
                  Selected coordinates:
                </span>
                {hasCoords ? (
                  <SoftBadge tone="primary" icon="my_location">
                    {latValue.toFixed(6)}, {lngValue.toFixed(6)}
                  </SoftBadge>
                ) : (
                  <SoftBadge tone="neutral" icon="location_searching">
                    Not set yet
                  </SoftBadge>
                )}
              </div>

              <Input
                label="Address (optional)"
                error={errors.addressText?.message}
                {...register('addressText')}
              />

              {/* Advanced: manual coordinate fallback for testers/developers */}
              <details className="border-t border-outline-variant/30 pt-sm">
                <summary className="cursor-pointer list-none text-label-sm font-semibold text-on-surface-variant marker:content-none">
                  <span className="inline-flex items-center gap-xs">
                    <Icon name="tune" className="text-[16px] leading-none" />
                    Advanced coordinates
                  </span>
                </summary>
                <div className="mt-sm flex flex-col gap-md">
                  <div className="grid grid-cols-1 gap-md sm:grid-cols-2">
                    <Input
                      label="Latitude"
                      inputMode="decimal"
                      error={errors.latitude?.message}
                      {...register('latitude', { onChange: markManual })}
                    />
                    <Input
                      label="Longitude"
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
                    I adjusted the location manually
                  </label>
                </div>
              </details>
            </div>
          </StepPanel>

          {/* Step 3 — Details */}
          <StepPanel
            active={step === STEP_DETAILS}
            title="3. Details"
            description={STEP_META[2].description}
          >
            <div className="flex flex-col gap-lg">
              <TextAreaField
                label="Description (optional)"
                placeholder="Anything that helps drivers find or judge this spot…"
                error={errors.description?.message}
                registration={register('description')}
              />

              <FieldGroup
                label="Suitable vehicle types"
                hint="Select all that fit."
                error={errors.suitableVehicleTypes?.message}
              >
                <div className="grid grid-cols-2 gap-sm sm:grid-cols-3">
                  {SPOT_VEHICLE_TYPES.map((type) => (
                    <SelectionCard
                      key={type}
                      type="checkbox"
                      value={type}
                      icon={VEHICLE_ICONS[type]}
                      label={humanizeEnum(type)}
                      registration={register('suitableVehicleTypes')}
                    />
                  ))}
                </div>
              </FieldGroup>

              <SelectField
                label="Parking context"
                error={errors.parkingContext?.message}
                registration={register('parkingContext')}
                options={PARKING_CONTEXTS}
              />

              <FieldGroup
                label="Legal status"
                hint="Spots marked illegal/risky cannot be created — the backend rejects them."
                error={errors.legalStatus?.message}
              >
                <div className="grid grid-cols-1 gap-sm sm:grid-cols-3">
                  {LEGAL_STATUSES.map((status) => (
                    <SelectionCard
                      key={status}
                      type="radio"
                      value={status}
                      icon={LEGAL_STATUS_META[status].icon}
                      label={humanizeEnum(status)}
                      description={LEGAL_STATUS_META[status].description}
                      tone={LEGAL_STATUS_META[status].tone}
                      registration={register('legalStatus')}
                    />
                  ))}
                </div>
              </FieldGroup>

              {legalStatus === 'ILLEGAL_OR_RISKY' ? (
                <>
                  <FieldGroup label="Violation reasons" error={errors.violationReasons?.message}>
                    <div className="flex flex-wrap gap-sm">
                      {VIOLATION_REASONS.map((reason) => (
                        <SelectionChip
                          key={reason}
                          value={reason}
                          label={humanizeEnum(reason)}
                          registration={register('violationReasons')}
                        />
                      ))}
                    </div>
                  </FieldGroup>
                  <div className="flex items-start gap-sm rounded-2xl bg-error/10 p-md text-error">
                    <Icon name="gpp_bad" className="text-[18px] leading-none" />
                    <p className="m-0 text-body-md">
                      The backend rejects creating spots marked illegal/risky
                      (ILLEGAL_SPOT_REJECTED). Submitting will fail.
                    </p>
                  </div>
                </>
              ) : null}
            </div>
          </StepPanel>

          {/* Step 4 — Review */}
          <StepPanel
            active={step === STEP_REVIEW}
            title="4. Review"
            description={STEP_META[3].description}
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
                    Your photo was uploaded successfully. Fix the details and submit again — the spot
                    will be created without re-uploading the photo.
                  </p>
                ) : null}
              </div>
            ) : null}

            {pending ? (
              <div className="mt-md">
                <LoadingState label={phase === 'uploading' ? 'Uploading photo…' : 'Creating spot…'} />
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
              Back
            </Button>

            {step < STEP_REVIEW ? (
              <Button type="button" onClick={() => void goNext()}>
                Next
                <Icon name="arrow_forward" className="text-[16px] leading-none" />
              </Button>
            ) : (
              <Button type="button" onClick={() => void submitCreate()} disabled={pending}>
                {phase === 'uploading'
                  ? 'Uploading photo…'
                  : phase === 'creating'
                    ? 'Creating spot…'
                    : 'Upload & create spot'}
              </Button>
            )}
          </div>
        </fieldset>
      </div>
    </WizardContainer>
  );
}

/* ------------------------------------------------------------------------- */
/* Wizard chrome                                                              */
/* ------------------------------------------------------------------------- */

function WizardContainer({ children }: { children: ReactNode }) {
  return (
    <div className="mx-auto w-full max-w-3xl px-md py-lg text-on-background md:px-xl">{children}</div>
  );
}

function WizardHeader({ step }: { step: number }) {
  const progress = ((step + 1) / TOTAL_STEPS) * 100;
  const meta = STEP_META[step];
  return (
    <div className="mb-lg">
      <p className="m-0 flex items-center gap-xs text-label-md font-semibold uppercase tracking-wider text-primary">
        <Icon name="add_location_alt" className="text-[16px] leading-none" />
        Share a spot
      </p>
      <div className="mt-sm flex flex-wrap items-end justify-between gap-sm">
        <h1 className="m-0 text-headline-lg-mobile text-on-surface md:text-headline-lg">
          {meta.title}
        </h1>
        <span className="text-label-md font-semibold text-on-surface-variant">
          Step {step + 1} of {TOTAL_STEPS}
        </span>
      </div>
      <p className="m-0 mt-xs text-body-md text-on-surface-variant">{meta.description}</p>
      <div
        className="mt-md h-1.5 w-full overflow-hidden rounded-full bg-surface-container-high"
        role="progressbar"
        aria-valuenow={step + 1}
        aria-valuemin={1}
        aria-valuemax={TOTAL_STEPS}
        aria-label={`Step ${step + 1} of ${TOTAL_STEPS}`}
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
  const description = (values.description ?? '').trim();
  const descriptionSnippet =
    description.length > 140 ? `${description.slice(0, 140)}…` : description;

  return (
    <div className="flex flex-col gap-md">
      {/* Photo preview */}
      <div className="flex flex-col gap-sm rounded-2xl bg-surface-container-low p-md sm:flex-row sm:items-center">
        <div className="h-28 w-full shrink-0 overflow-hidden rounded-xl bg-surface-container-high sm:w-40">
          {previewUrl ? (
            <img src={previewUrl} alt="Spot preview" className="h-full w-full object-cover" />
          ) : (
            <div className="flex h-full w-full items-center justify-center text-on-surface-variant">
              <Icon name="image" className="text-[32px] leading-none" />
            </div>
          )}
        </div>
        <div className="min-w-0 flex-1">
          <p className="m-0 truncate text-body-md font-semibold text-on-surface">
            {fileName ?? 'Photo'}
          </p>
          {fileSize !== null ? (
            <p className="m-0 mt-xs text-label-sm text-on-surface-variant">
              {formatFileSize(fileSize)}
            </p>
          ) : null}
          {uploaded ? (
            <div className="mt-sm">
              <SoftBadge tone="success" icon="cloud_done">
                Uploaded — reused on submit
              </SoftBadge>
            </div>
          ) : null}
        </div>
      </div>

      <ReviewRow icon="my_location" label="Coordinates">
        {hasCoords ? `${latValue.toFixed(6)}, ${lngValue.toFixed(6)}` : 'Not set'}
      </ReviewRow>

      {values.addressText && values.addressText.trim() !== '' ? (
        <ReviewRow icon="home_pin" label="Address">
          {values.addressText}
        </ReviewRow>
      ) : null}

      {descriptionSnippet !== '' ? (
        <ReviewRow icon="description" label="Description">
          {descriptionSnippet}
        </ReviewRow>
      ) : null}

      <ReviewRow icon="directions_car" label="Vehicle types">
        {values.suitableVehicleTypes.length > 0 ? (
          <span className="flex flex-wrap gap-xs">
            {values.suitableVehicleTypes.map((type) => (
              <SoftBadge key={type} tone="neutral">
                {humanizeEnum(type)}
              </SoftBadge>
            ))}
          </span>
        ) : (
          '—'
        )}
      </ReviewRow>

      <ReviewRow icon="local_parking" label="Parking context">
        {values.parkingContext ? humanizeEnum(values.parkingContext) : '—'}
      </ReviewRow>

      <ReviewRow icon="gavel" label="Legal status">
        {values.legalStatus ? (
          <SoftBadge tone={LEGAL_STATUS_META[values.legalStatus].tone}>
            {humanizeEnum(values.legalStatus)}
          </SoftBadge>
        ) : (
          '—'
        )}
      </ReviewRow>

      {values.violationReasons.length > 0 ? (
        <ReviewRow icon="warning" label="Violation reasons">
          <span className="flex flex-wrap gap-xs">
            {values.violationReasons.map((reason) => (
              <SoftBadge key={reason} tone="danger" icon="warning">
                {humanizeEnum(reason)}
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
  return (
    <Surface level="raised" className="rounded-3xl p-lg">
      <div className="flex flex-col items-center gap-md py-lg text-center">
        <span className="flex h-20 w-20 items-center justify-center rounded-full bg-secondary-container text-on-secondary-container">
          <Icon name="check" className="text-[40px] leading-none" />
        </span>
        <h2 className="m-0 text-headline-md text-on-surface">Spot created</h2>
        <StatusBadge status={spot.status} />
        <p className="m-0 max-w-sm text-body-md text-on-surface-variant">
          Thanks for contributing. Redirecting you to your new spot…
        </p>
        <Link
          to={`/spots/${spot.id}`}
          className="inline-flex items-center gap-sm rounded-full bg-primary px-lg py-sm text-label-md text-on-primary no-underline shadow-sm transition-all duration-std hover:bg-primary/90"
        >
          View your spot now
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
        <option value="">— select —</option>
        {options.map((option) => (
          <option key={option} value={option}>
            {humanizeEnum(option)}
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
