import { zodResolver } from '@hookform/resolvers/zod';
import {
  PARKING_CONTEXTS,
  LEGAL_STATUSES,
  SPOT_VEHICLE_TYPES,
  VIOLATION_REASONS,
  type UploadMediaResponse,
} from '@parkio/types';
import { createIdempotencyKey } from '@parkio/api-client';
import { Button, Card, Input, PageShell, colors, radius, spacing } from '@parkio/ui';
import {
  createSpotFormSchema,
  mediaUploadSchema,
  type CreateSpotFormValues,
} from '@parkio/validation';
import { useState, type ChangeEvent, type ReactNode } from 'react';
import { useForm, type UseFormRegisterReturn } from 'react-hook-form';
import { useNavigate } from 'react-router-dom';
import { mediaApi, parkingApi } from '@/api';
import { ApiErrorMessage } from '@/components/ApiErrorMessage';
import { AppNav } from '@/components/AppNav';

type SubmitPhase = 'idle' | 'uploading' | 'creating';

export function UploadPage() {
  const navigate = useNavigate();
  const [file, setFile] = useState<File | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  // Kept after a successful upload so a failed create-spot can be retried
  // without re-uploading; cleared when the user picks a different file.
  const [uploadedMedia, setUploadedMedia] = useState<UploadMediaResponse | null>(null);
  const [phase, setPhase] = useState<SubmitPhase>('idle');
  const [submitError, setSubmitError] = useState<unknown>(null);

  const {
    register,
    handleSubmit,
    watch,
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

  const onFileChange = (event: ChangeEvent<HTMLInputElement>) => {
    const selected = event.target.files?.[0] ?? null;
    setFile(selected);
    setUploadedMedia(null);
    setFileError(null);
    if (selected) {
      const check = mediaUploadSchema.safeParse({ file: selected });
      if (!check.success) {
        setFileError(check.error.issues[0]?.message ?? 'Invalid file');
      }
    }
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
      navigate(`/spots/${spot.id}`);
    } catch (error) {
      setSubmitError(error);
    } finally {
      setPhase('idle');
    }
  });

  const pending = phase !== 'idle';

  return (
    <PageShell title="Share a parking spot">
      <AppNav />
      <form onSubmit={onSubmit} style={{ maxWidth: '40rem' }}>
        <fieldset
          disabled={pending}
          style={{ border: 'none', padding: 0, margin: 0, display: 'flex', flexDirection: 'column', gap: '1rem' }}
        >
          <Card title="1. Photo">
            <div style={{ display: 'flex', flexDirection: 'column', gap: spacing.sm }}>
              <input type="file" accept="image/jpeg,image/png,image/webp" onChange={onFileChange} />
              <p style={{ margin: 0, fontSize: '0.75rem', color: colors.textMuted }}>
                JPEG, PNG or WebP, up to 10MB. The photo's signed viewing URL is generated later on
                the spot detail page.
              </p>
              {uploadedMedia ? (
                <p style={{ margin: 0, fontSize: '0.875rem' }}>
                  Photo already uploaded — it will be reused unless you choose another file.
                </p>
              ) : null}
              {fileError ? (
                <span style={{ fontSize: '0.75rem', color: colors.error }}>{fileError}</span>
              ) : null}
            </div>
          </Card>

          <Card title="2. Location">
            <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
              <p style={{ margin: 0, fontSize: '0.75rem', color: colors.textMuted }}>
                Enter coordinates manually for now — GPS / map picker integration comes later.
              </p>
              <div style={{ display: 'flex', gap: '1rem' }}>
                <Input label="Latitude" inputMode="decimal" error={errors.latitude?.message} {...register('latitude')} />
                <Input label="Longitude" inputMode="decimal" error={errors.longitude?.message} {...register('longitude')} />
              </div>
              <Input label="Address (optional)" error={errors.addressText?.message} {...register('addressText')} />
              <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', fontSize: '0.875rem' }}>
                <input type="checkbox" {...register('manualLocationEdited')} />
                I adjusted the location manually
              </label>
            </div>
          </Card>

          <Card title="3. Spot details">
            <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
              <Input label="Description (optional)" error={errors.description?.message} {...register('description')} />

              <CheckboxGroup label="Suitable vehicle types" error={errors.suitableVehicleTypes?.message}>
                {SPOT_VEHICLE_TYPES.map((type) => (
                  <label key={type} style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', fontSize: '0.875rem' }}>
                    <input type="checkbox" value={type} {...register('suitableVehicleTypes')} />
                    {type}
                  </label>
                ))}
              </CheckboxGroup>

              <SelectField
                label="Parking context"
                error={errors.parkingContext?.message}
                registration={register('parkingContext')}
                options={PARKING_CONTEXTS}
              />

              <SelectField
                label="Legal status"
                error={errors.legalStatus?.message}
                registration={register('legalStatus')}
                options={LEGAL_STATUSES}
              />
              <p style={{ margin: 0, fontSize: '0.75rem', color: colors.textMuted }}>
                Spots marked illegal/risky cannot be created — the backend rejects them.
              </p>

              {legalStatus === 'ILLEGAL_OR_RISKY' ? (
                <>
                  <CheckboxGroup label="Violation reasons" error={errors.violationReasons?.message}>
                    {VIOLATION_REASONS.map((reason) => (
                      <label key={reason} style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', fontSize: '0.875rem' }}>
                        <input type="checkbox" value={reason} {...register('violationReasons')} />
                        {reason}
                      </label>
                    ))}
                  </CheckboxGroup>
                  <p style={{ margin: 0, fontSize: '0.875rem', color: colors.error }}>
                    Warning: the backend rejects creating spots marked as illegal/risky
                    (ILLEGAL_SPOT_REJECTED). Submitting will fail.
                  </p>
                </>
              ) : null}
            </div>
          </Card>

          {submitError !== null ? (
            <div style={{ display: 'flex', flexDirection: 'column', gap: spacing.sm }}>
              <ApiErrorMessage error={submitError} />
              {uploadedMedia ? (
                <p style={{ margin: 0, fontSize: '0.875rem', color: colors.textMuted }}>
                  Your photo was uploaded successfully. Fix the details and submit again — the spot
                  will be created without re-uploading the photo.
                </p>
              ) : null}
            </div>
          ) : null}

          <Button type="submit" disabled={pending}>
            {phase === 'uploading'
              ? 'Uploading photo…'
              : phase === 'creating'
                ? 'Creating spot…'
                : 'Upload & create spot'}
          </Button>
        </fieldset>
      </form>
    </PageShell>
  );
}

function CheckboxGroup({ label, error, children }: { label: string; error?: string; children: ReactNode }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: spacing.xs }}>
      <span style={{ fontSize: '0.875rem', fontWeight: 500, color: colors.text }}>{label}</span>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: spacing.sm }}>{children}</div>
      {error ? <span style={{ fontSize: '0.75rem', color: colors.error }}>{error}</span> : null}
    </div>
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
    <label style={{ display: 'flex', flexDirection: 'column', gap: spacing.xs }}>
      <span style={{ fontSize: '0.875rem', fontWeight: 500, color: colors.text }}>{label}</span>
      <select
        style={{
          padding: spacing.sm,
          borderRadius: radius.md,
          border: `1px solid ${error ? colors.error : colors.border}`,
          fontSize: '1rem',
          backgroundColor: colors.surface,
        }}
        {...registration}
      >
        <option value="">— select —</option>
        {options.map((option) => (
          <option key={option} value={option}>
            {option}
          </option>
        ))}
      </select>
      {error ? <span style={{ fontSize: '0.75rem', color: colors.error }}>{error}</span> : null}
    </label>
  );
}
