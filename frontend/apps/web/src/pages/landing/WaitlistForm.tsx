import { type FormEvent, useId, useState } from 'react';
import { LandingIcon } from './LandingIcon';
import { WAITLIST_SOURCE, type WaitlistRole, submitWaitlistInterest } from './waitlistService';

interface FormState {
  email: string;
  city: string;
  role: '' | WaitlistRole;
  betaUpdatesConsent: boolean;
  researchConsent: boolean;
}

const INITIAL_STATE: FormState = {
  email: '',
  city: '',
  role: '',
  betaUpdatesConsent: false,
  researchConsent: false,
};

function isValidEmail(email: string) {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email);
}

export function WaitlistForm() {
  const emailId = useId();
  const cityId = useId();
  const roleId = useId();
  const consentId = useId();
  const researchId = useId();
  const statusId = useId();
  const [form, setForm] = useState<FormState>(INITIAL_STATE);
  const [submitting, setSubmitting] = useState(false);
  const [submitted, setSubmitted] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);

    if (!isValidEmail(form.email)) {
      setError('Enter a valid email address.');
      return;
    }

    if (!form.betaUpdatesConsent) {
      setError('Consent is required to receive beta updates.');
      return;
    }

    setSubmitting(true);
    try {
      await submitWaitlistInterest({
        email: form.email.trim(),
        city: form.city.trim() || undefined,
        role: form.role || undefined,
        betaUpdatesConsent: form.betaUpdatesConsent,
        researchConsent: form.researchConsent,
        consentTimestamp: new Date().toISOString(),
        source: WAITLIST_SOURCE,
      });
      setSubmitted(true);
      setForm(INITIAL_STATE);
    } catch {
      setError('The waitlist could not submit. Try again in a moment.');
    } finally {
      setSubmitting(false);
    }
  }

  if (submitted) {
    return (
      <section className="landing-form landing-form--success" aria-labelledby={statusId}>
        <div className="landing-form__success-icon" aria-hidden="true">
          <LandingIcon name="check" />
        </div>
        <h3 id={statusId}>Your Parkio beta interest is ready.</h3>
        <p>
          Thanks for your interest. Parkio is preparing for hosted beta; invite timing, supported
          areas, and cohort size are not yet guaranteed.
        </p>
        <button
          className="landing-link-button"
          type="button"
          onClick={() => {
            setSubmitted(false);
            setError(null);
          }}
        >
          Submit another beta-interest form
        </button>
      </section>
    );
  }

  return (
    <form className="landing-form" onSubmit={handleSubmit} noValidate aria-describedby={statusId}>
      <div>
        <p className="landing-eyebrow">Beta waitlist</p>
        <h3>Join the future hosted-beta list.</h3>
        <p>
          Invites, supported areas, and timing are TBD. Parkio uses this form for beta
          communication planning and hosted-beta invite coordination.
        </p>
      </div>

      <div className="landing-form__grid">
        <label className="landing-field" htmlFor={emailId}>
          Email
          <input
            id={emailId}
            type="email"
            autoComplete="email"
            value={form.email}
            onChange={(event) => setForm((current) => ({ ...current, email: event.target.value }))}
            required
            aria-invalid={Boolean(error && !isValidEmail(form.email))}
          />
        </label>
        <label className="landing-field" htmlFor={roleId}>
          Role <span>Optional</span>
          <select
            id={roleId}
            value={form.role}
            onChange={(event) =>
              setForm((current) => ({ ...current, role: event.target.value as FormState['role'] }))
            }
          >
            <option value="">Select a role</option>
            <option value="driver">Driver</option>
            <option value="tester">Tester</option>
            <option value="partner">Partner</option>
          </select>
        </label>
        <label className="landing-field landing-field--wide" htmlFor={cityId}>
          City or general area <span>Optional</span>
          <input
            id={cityId}
            type="text"
            autoComplete="address-level2"
            value={form.city}
            onChange={(event) => setForm((current) => ({ ...current, city: event.target.value }))}
            placeholder="TBD launch areas"
          />
        </label>
      </div>

      <label className="landing-check" htmlFor={consentId}>
        <input
          id={consentId}
          type="checkbox"
          checked={form.betaUpdatesConsent}
          onChange={(event) =>
            setForm((current) => ({ ...current, betaUpdatesConsent: event.target.checked }))
          }
        />
        <span>
          I agree to receive Parkio beta updates and understand hosted-beta access is not guaranteed.
        </span>
      </label>

      <label className="landing-check" htmlFor={researchId}>
        <input
          id={researchId}
          type="checkbox"
          checked={form.researchConsent}
          onChange={(event) =>
            setForm((current) => ({ ...current, researchConsent: event.target.checked }))
          }
        />
        <span>Parkio may contact me for optional product feedback.</span>
      </label>

      <p id={statusId} className="landing-form__status" role={error ? 'alert' : 'status'}>
        {error ??
          'Parkio will use this information for beta communication planning. Review the privacy page for beta data-use details.'}
      </p>

      <button className="landing-button landing-button--primary landing-form__submit" type="submit" disabled={submitting}>
        {submitting ? (
          <>
            <LandingIcon name="progress_activity" className="landing-spin" />
            Joining waitlist
          </>
        ) : (
          <>
            Join Beta Waitlist
            <LandingIcon name="arrow_forward" />
          </>
        )}
      </button>
    </form>
  );
}
