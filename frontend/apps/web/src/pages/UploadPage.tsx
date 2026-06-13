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
  Card,
  Icon,
  Input,
  LoadingState,
  PageShell,
  SoftBadge,
  StatusBadge,
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
import { AppNav } from '@/components/AppNav';
import { MapPicker } from '@/components/map/MapPicker';
import { isValidLatLng } from '@/components/map/mapConfig';
import { humanizeEnum } from '@/lib/format';

type SubmitPhase = 'idle' | 'uploading' | 'creating';
type StepStatus = 'done' | 'active' | 'pending';

const ACCEPTED_TYPES = 'image/jpeg,image/png,image/webp';

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
 * Upload & Create Spot Beta (`/upload`). Applies the V2 design (two-column
 * desktop layout, premium upload hero, progress stepper, selection cards,
 * contribution panel) while preserving the exact create flow: validate file →
 * upload media (reused on retry, cleared when the file changes) → create spot
 * with idempotency keys → confirm → redirect to the new spot.
 */
export function UploadPage() {
  const navigate = useNavigate();
  const fileInputRef = useRef<HTMLInputElement>(null);

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

  const {
    register,
    handleSubmit,
    watch,
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

  const legalStatus = watch('legalStatus');

  // Editing a coordinate field or clicking the map both count as a manual edit.
  const markManual = () => setValue('manualLocationEdited', true);

  const latValue = Number(watch('latitude'));
  const lngValue = Number(watch('longitude'));
  const hasCoords = isValidLatLng(latValue, lngValue);
  const pickerLat = hasCoords ? latValue : null;
  const pickerLng = hasCoords ? lngValue : null;

  const handlePick = (lat: number, lng: number) => {
    setValue('latitude', Number(lat.toFixed(6)), { shouldValidate: true });
    setValue('longitude', Number(lng.toFixed(6)), { shouldValidate: true });
    setValue('manualLocationEdited', true);
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

  const onSubmit = handleSubmit(async (values) => {
    setSubmitError(null);

    if (!uploadedMedia) {
      if (!file) {
        setFileError('Choose a photo to upload');
        return;
      }
      const check = mediaUploadSchema.safeParse({ file });
      if (!check.success) {
        setFileError(check.error.issues[0]?.message ?? 'Invalid file');
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
          latitude: values.latitude,
          longitude: values.longitude,
          addressText: values.addressText || undefined,
          description: values.description || undefined,
          manualLocationEdited: values.manualLocationEdited,
          suitableVehicleTypes: values.suitableVehicleTypes,
          parkingContext: values.parkingContext,
          legalStatus: values.legalStatus,
          violationReasons: values.violationReasons.length > 0 ? values.violationReasons : undefined,
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
  const steps = buildSteps({
    hasFile: file !== null || uploadedMedia !== null,
    mediaReady: uploadedMedia !== null,
    uploading: phase === 'uploading',
    creating: phase === 'creating',
    created: createdSpot !== null,
  });

  return (
    <PageShell title="Share a parking spot">
      <AppNav />

      <div className="grid grid-cols-1 gap-lg lg:grid-cols-3 lg:items-start">
        {/* Left: the upload flow (or the success confirmation once created) */}
        <div className="flex flex-col gap-lg lg:col-span-2">
          {createdSpot ? (
            <SuccessPanel spot={createdSpot} />
          ) : (
            <form onSubmit={onSubmit}>
              <fieldset
                disabled={pending}
                className="m-0 flex flex-col gap-lg border-0 p-0"
              >
                {/* 1 — Upload hero */}
                <Card title="1. Photo">
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
                      <div className="flex flex-col gap-sm rounded-xl border border-outline-variant/40 bg-surface-container-low p-md sm:flex-row sm:items-center">
                        <div className="h-28 w-full shrink-0 overflow-hidden rounded-lg bg-surface-container-high sm:w-40">
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
                          'flex min-h-[200px] cursor-pointer flex-col items-center justify-center gap-sm rounded-xl border-2 border-dashed p-xl text-center transition-colors duration-std',
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
                      The photo&apos;s signed viewing URL is generated later, on the spot detail
                      page.
                    </p>
                    {fileError ? (
                      <p className="m-0 flex items-center gap-xs text-label-sm text-error">
                        <Icon name="error" className="text-[14px] leading-none" />
                        {fileError}
                      </p>
                    ) : null}
                  </div>
                </Card>

                {/* 2 — Location */}
                <Card title="2. Location">
                  <div className="flex flex-col gap-md">
                    <p className="m-0 text-label-sm text-on-surface-variant">
                      Click the map to set location. Coordinates can also be entered manually.
                      Address geocoding is not available yet.
                    </p>
                    <div className="overflow-hidden rounded-xl border border-outline-variant/40">
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
                    <Input
                      label="Address (optional)"
                      error={errors.addressText?.message}
                      {...register('addressText')}
                    />
                    <label className="flex items-center gap-sm text-body-md text-on-surface">
                      <input
                        type="checkbox"
                        className="h-4 w-4 rounded border-outline-variant text-primary focus:ring-primary"
                        {...register('manualLocationEdited')}
                      />
                      I adjusted the location manually
                    </label>
                  </div>
                </Card>

                {/* 3 — Spot details */}
                <Card title="3. Spot details">
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
                        <FieldGroup
                          label="Violation reasons"
                          error={errors.violationReasons?.message}
                        >
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
                        <div className="flex items-start gap-sm rounded-xl bg-error/10 p-md text-error">
                          <Icon name="gpp_bad" className="text-[18px] leading-none" />
                          <p className="m-0 text-body-md">
                            The backend rejects creating spots marked illegal/risky
                            (ILLEGAL_SPOT_REJECTED). Submitting will fail.
                          </p>
                        </div>
                      </>
                    ) : null}
                  </div>
                </Card>

                {/* Error & retry */}
                {submitError !== null ? (
                  <div className="flex flex-col gap-sm">
                    <FriendlyApiErrorMessage error={submitError} />
                    {uploadedMedia ? (
                      <p className="m-0 flex items-start gap-sm rounded-xl bg-surface-container-low p-md text-body-md text-on-surface-variant">
                        <Icon name="cloud_done" className="text-[18px] leading-none text-secondary" />
                        Your photo was uploaded successfully. Fix the details and submit again — the
                        spot will be created without re-uploading the photo.
                      </p>
                    ) : null}
                  </div>
                ) : null}

                <Button type="submit" disabled={pending} className="w-full sm:w-auto">
                  {phase === 'uploading'
                    ? 'Uploading photo…'
                    : phase === 'creating'
                      ? 'Creating spot…'
                      : 'Upload & create spot'}
                </Button>
              </fieldset>
            </form>
          )}
        </div>

        {/* Right: live progress + trust & contribution panel */}
        <aside className="flex flex-col gap-md lg:sticky lg:top-lg">
          <ProgressPanel steps={steps} phase={phase} />
          <ContributionPanel />
        </aside>
      </div>
    </PageShell>
  );
}

/* ------------------------------------------------------------------------- */
/* Progress                                                                   */
/* ------------------------------------------------------------------------- */

interface Step {
  label: string;
  status: StepStatus;
}

function buildSteps(s: {
  hasFile: boolean;
  mediaReady: boolean;
  uploading: boolean;
  creating: boolean;
  created: boolean;
}): Step[] {
  const downstream = s.mediaReady || s.creating || s.created;
  return [
    { label: 'Select photo', status: s.hasFile ? 'done' : 'active' },
    {
      label: 'Uploading photo',
      status: s.uploading ? 'active' : downstream ? 'done' : 'pending',
    },
    { label: 'Photo uploaded', status: downstream ? 'done' : 'pending' },
    {
      label: 'Creating spot',
      status: s.creating ? 'active' : s.created ? 'done' : 'pending',
    },
    { label: 'Spot created', status: s.created ? 'done' : 'pending' },
  ];
}

const STEP_BADGE: Record<StepStatus, { tone: BadgeTone; icon: string; text: string }> = {
  done: { tone: 'success', icon: 'check', text: 'Done' },
  active: { tone: 'primary', icon: 'sync', text: 'In progress' },
  pending: { tone: 'neutral', icon: 'schedule', text: 'Pending' },
};

function ProgressPanel({ steps, phase }: { steps: Step[]; phase: SubmitPhase }) {
  return (
    <Card title="Progress">
      <ol className="m-0 flex list-none flex-col gap-sm p-0">
        {steps.map((step) => {
          const badge = STEP_BADGE[step.status];
          return (
            <li
              key={step.label}
              className={cn(
                'flex items-center justify-between gap-sm rounded-lg px-sm py-xs',
                step.status === 'active' ? 'bg-primary/5' : null,
              )}
            >
              <span
                className={cn(
                  'flex items-center gap-sm text-body-md',
                  step.status === 'pending' ? 'text-on-surface-variant' : 'text-on-surface',
                )}
              >
                <Icon
                  name={step.status === 'done' ? 'check_circle' : 'radio_button_unchecked'}
                  filled={step.status === 'done'}
                  className={cn(
                    'text-[18px] leading-none',
                    step.status === 'done' ? 'text-secondary' : 'text-outline-variant',
                  )}
                />
                {step.label}
              </span>
              <SoftBadge tone={badge.tone} icon={badge.icon}>
                {badge.text}
              </SoftBadge>
            </li>
          );
        })}
      </ol>
      {phase !== 'idle' ? (
        <div className="mt-sm">
          <LoadingState label={phase === 'uploading' ? 'Uploading photo…' : 'Creating spot…'} />
        </div>
      ) : null}
    </Card>
  );
}

/* ------------------------------------------------------------------------- */
/* Trust & contribution panel                                                 */
/* ------------------------------------------------------------------------- */

const CONTRIBUTION_NOTES: { icon: string; title: string; body: string }[] = [
  {
    icon: 'verified',
    title: 'Why verification matters',
    body: 'Other drivers can confirm your spot. Verified spots are trusted faster and surface more prominently.',
  },
  {
    icon: 'groups',
    title: 'Community trust',
    body: 'Parkio is built by drivers helping drivers. Accurate, honest listings keep the map reliable for everyone.',
  },
  {
    icon: 'schedule',
    title: 'Freshness',
    body: 'Spots reflect a moment in time. Recently confirmed spots read as fresher; older ones gradually go stale.',
  },
  {
    icon: 'add_location_alt',
    title: 'Your contribution',
    body: 'Every spot you add expands coverage and helps someone find parking a little faster.',
  },
];

function ContributionPanel() {
  return (
    <Card title="Contribution & trust">
      <ul className="m-0 flex list-none flex-col gap-md p-0">
        {CONTRIBUTION_NOTES.map((note) => (
          <li key={note.title} className="flex gap-sm">
            <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
              <Icon name={note.icon} className="text-[18px] leading-none" />
            </span>
            <div className="min-w-0">
              <p className="m-0 text-body-md font-semibold text-on-surface">{note.title}</p>
              <p className="m-0 mt-xs text-label-sm text-on-surface-variant">{note.body}</p>
            </div>
          </li>
        ))}
      </ul>
    </Card>
  );
}

/* ------------------------------------------------------------------------- */
/* Success                                                                    */
/* ------------------------------------------------------------------------- */

function SuccessPanel({ spot }: { spot: Spot }) {
  return (
    <Card>
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
    </Card>
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
