import { BrandMark } from './landing/BrandMark';
import { LandingIcon } from './landing/LandingIcon';
import {
  GITHUB_REPO_URL,
  PARKIO_DOMAIN,
  PRODUCT_DESCRIPTION,
  STAGE_DESCRIPTION,
  TECHNICAL_DOCS_URL,
  betaFacts,
  faqItems,
  featureGroups,
  navItems,
  problemPoints,
  technologyPoints,
  trustItems,
  workflowSteps,
} from './landing/landingContent';
import { ProductWorkflowPreview } from './landing/ProductWorkflowPreview';
import { WaitlistForm } from './landing/WaitlistForm';

function LandingHeader() {
  return (
    <header className="landing-header">
      <nav className="landing-nav" aria-label="Landing page navigation">
        <BrandMark />
        <div className="landing-nav__links" aria-label="Page sections">
          {navItems.map((item) => (
            <a key={item.href} href={item.href}>
              {item.label}
            </a>
          ))}
        </div>
        <a className="landing-button landing-button--primary landing-nav__cta" href="#waitlist">
          Join Beta Waitlist
        </a>
      </nav>
    </header>
  );
}

function SectionIntro({
  eyebrow,
  title,
  children,
}: {
  eyebrow: string;
  title: string;
  children: string;
}) {
  return (
    <div className="landing-section__intro">
      <p className="landing-eyebrow">{eyebrow}</p>
      <h2>{title}</h2>
      <p>{children}</p>
    </div>
  );
}

function HeroSection() {
  return (
    <section className="landing-hero" aria-labelledby="landing-title">
        <div className="landing-container landing-hero__grid">
        <div className="landing-hero__copy">
          <p className="landing-stage">
            <LandingIcon name="radio_button_checked" />
            Release-candidate / hosted-beta preparation
          </p>
          <h1 id="landing-title">
            Parking intelligence powered by real drivers.
          </h1>
          <p className="landing-hero__lead">{PRODUCT_DESCRIPTION}</p>
          <p className="landing-hero__status">{STAGE_DESCRIPTION}</p>
          <div className="landing-hero__actions" aria-label="Primary actions">
            <a className="landing-button landing-button--primary" href="#waitlist">
              Join Beta Waitlist
              <LandingIcon name="arrow_forward" />
            </a>
            <a className="landing-button landing-button--secondary" href={TECHNICAL_DOCS_URL}>
              View Technical Documentation
              <LandingIcon name="open_in_new" />
            </a>
          </div>
          <dl className="landing-proof">
            <div>
              <dt>Domain</dt>
              <dd>{PARKIO_DOMAIN}</dd>
            </div>
            <div>
              <dt>Stage</dt>
              <dd>RC1 / beta prep</dd>
            </div>
            <div>
              <dt>Production</dt>
              <dd>Not live yet</dd>
            </div>
          </dl>
        </div>
        <ProductWorkflowPreview />
      </div>
    </section>
  );
}

function ProblemSection() {
  return (
    <section className="landing-section" id="product" aria-labelledby="problem-title">
      <div className="landing-container">
        <SectionIntro eyebrow="Problem" title="Parking should not depend on stale guesses.">
          Drivers often make parking decisions with incomplete, outdated, or scattered information.
          Real availability changes quickly, and the people closest to the curb usually have the
          freshest signal.
        </SectionIntro>
        <div className="landing-card-grid landing-card-grid--three">
          {problemPoints.map((point) => (
            <article className="landing-card" key={point.title}>
              <LandingIcon name={point.icon} className="landing-card__icon" />
              <h3>{point.title}</h3>
              <p>{point.body}</p>
            </article>
          ))}
        </div>
      </div>
    </section>
  );
}

function SolutionSection() {
  return (
    <section className="landing-section landing-section--tint" aria-labelledby="solution-title">
      <div className="landing-container landing-split">
        <div>
          <p className="landing-eyebrow">Solution</p>
          <h2 id="solution-title">A shared signal for real-world parking.</h2>
          <p>
            Parkio turns driver observations into a practical product loop: share what is available,
            verify what changed, claim what you use, and manage return context after arrival.
          </p>
          <a className="landing-text-link" href="#how-it-works">
            See how Parkio works
            <LandingIcon name="arrow_downward" />
          </a>
        </div>
        <ul className="landing-signal-list" aria-label="Parkio solution signals">
          <li>
            <LandingIcon name="add_location_alt" />
            Drivers share available spots.
          </li>
          <li>
            <LandingIcon name="verified" />
            Other drivers verify, claim, or report changes.
          </li>
          <li>
            <LandingIcon name="shield" />
            Trust signals help the community judge freshness.
          </li>
          <li>
            <LandingIcon name="notifications_active" />
            Smart Return and notifications support parking after arrival.
          </li>
        </ul>
      </div>
    </section>
  );
}

function HowItWorksSection() {
  return (
    <section className="landing-section" id="how-it-works" aria-labelledby="workflow-title">
      <div className="landing-container">
        <SectionIntro eyebrow="How it works" title="Four steps, one parking signal loop.">
          The landing page keeps the workflow simple: find nearby parking intelligence, share what
          changed, verify freshness, and return with better context.
        </SectionIntro>
        <ol className="landing-steps">
          {workflowSteps.map((step, index) => (
            <li className="landing-step" key={step.title}>
              <span className="landing-step__count">{String(index + 1).padStart(2, '0')}</span>
              <LandingIcon name={step.icon} className="landing-step__icon" />
              <h3>{step.title}</h3>
              <p>{step.body}</p>
            </li>
          ))}
        </ol>
      </div>
    </section>
  );
}

function FeaturesSection() {
  return (
    <section className="landing-section landing-section--compact" aria-labelledby="features-title">
      <div className="landing-container">
        <SectionIntro eyebrow="Features" title="Useful beta scope without inflated claims.">
          Parkio focuses on the parking decisions that benefit from fresh community signals.
        </SectionIntro>
        <div className="landing-feature-grid">
          {featureGroups.map((feature) => (
            <article className="landing-feature" key={feature.title}>
              <LandingIcon name={feature.icon} className="landing-feature__icon" />
              <h3>{feature.title}</h3>
              <p>{feature.body}</p>
            </article>
          ))}
        </div>
      </div>
    </section>
  );
}

function TrustSection() {
  return (
    <section className="landing-section landing-section--ink" id="trust" aria-labelledby="trust-title">
      <div className="landing-container landing-split">
        <div>
          <p className="landing-eyebrow">Trust and privacy</p>
          <h2 id="trust-title">Built for trust, not surveillance.</h2>
          <p>
            Parkio should make location-related decisions easier without turning user movement into
            marketing inventory. Public beta data collection details must be documented before
            launch.
          </p>
          <a className="landing-button landing-button--light" href="#faq">
            Read the FAQ
          </a>
        </div>
        <div className="landing-trust-list">
          {trustItems.map((item) => (
            <article key={item.title}>
              <LandingIcon name={item.icon} />
              <div>
                <h3>{item.title}</h3>
                <p>{item.body}</p>
              </div>
            </article>
          ))}
        </div>
      </div>
    </section>
  );
}

function TechnologySection() {
  return (
    <section className="landing-section" id="technology" aria-labelledby="technology-title">
      <div className="landing-container landing-tech">
        <div>
          <p className="landing-eyebrow">Technology</p>
          <h2 id="technology-title">Engineered for hosted-beta readiness.</h2>
          <p>
            Parkio is not a landing-page-only concept. The repository documents release-candidate
            engineering work, certification evidence, and hosted-beta operating plans.
          </p>
          <a className="landing-button landing-button--secondary" href={TECHNICAL_DOCS_URL}>
            View Technical Documentation
            <LandingIcon name="open_in_new" />
          </a>
        </div>
        <div className="landing-tech__panel">
          <p className="landing-eyebrow">Diligence summary</p>
          <ul>
            {technologyPoints.map((point) => (
              <li key={point}>
                <LandingIcon name="check_circle" />
                <span>{point}</span>
              </li>
            ))}
          </ul>
        </div>
      </div>
    </section>
  );
}

function HostedBetaSection() {
  return (
    <section className="landing-section landing-section--tint" id="beta" aria-labelledby="beta-title">
      <div className="landing-container landing-beta">
        <div>
          <p className="landing-eyebrow">Hosted beta</p>
          <h2 id="beta-title">Preparing for a focused hosted beta.</h2>
          <p>
            Parkio is preparing for hosted beta, not a broad public launch. Early testers should be
            comfortable with a release-candidate product and giving feedback.
          </p>
          <div className="landing-hero__actions">
            <a className="landing-button landing-button--primary" href="#waitlist">
              Join Beta Waitlist
            </a>
            <span className="landing-button landing-button--secondary landing-button--disabled" aria-disabled="true">
              Contact founder TBD
            </span>
          </div>
        </div>
        <dl className="landing-beta__facts">
          {betaFacts.map((fact) => (
            <div key={fact.label}>
              <dt>{fact.label}</dt>
              <dd>{fact.value}</dd>
            </div>
          ))}
        </dl>
      </div>
    </section>
  );
}

function FAQSection() {
  return (
    <section className="landing-section" id="faq" aria-labelledby="faq-title">
      <div className="landing-container">
        <SectionIntro eyebrow="FAQ" title="Clear answers before the waitlist.">
          The public page should reduce confusion about stage, privacy, supported areas, and
          production readiness.
        </SectionIntro>
        <div className="landing-faq">
          {faqItems.map((item) => (
            <details key={item.question}>
              <summary>{item.question}</summary>
              <p>{item.answer}</p>
            </details>
          ))}
        </div>
      </div>
    </section>
  );
}

function Footer() {
  return (
    <footer className="landing-footer">
      <div className="landing-container landing-footer__grid">
        <div>
          <BrandMark />
          <p>{PRODUCT_DESCRIPTION}</p>
          <p className="landing-footer__stage">Release-candidate / hosted-beta preparation.</p>
        </div>
        <nav aria-label="Footer links">
          <a href={TECHNICAL_DOCS_URL}>Documentation</a>
          <a href={GITHUB_REPO_URL}>Follow Development</a>
          <span>Contact founder: TBD</span>
          <a href="/terms">Terms</a>
          <a href="/privacy">Privacy</a>
        </nav>
      </div>
    </footer>
  );
}

export function LandingPage() {
  return (
    <div className="landing-page">
      <LandingHeader />
      <main>
        <span className="landing-route-focus" data-route-focus aria-hidden="true" />
        <HeroSection />
        <ProblemSection />
        <SolutionSection />
        <HowItWorksSection />
        <FeaturesSection />
        <TrustSection />
        <TechnologySection />
        <HostedBetaSection />
        <section className="landing-section landing-section--waitlist" id="waitlist" aria-labelledby="waitlist-title">
          <div className="landing-container landing-waitlist">
            <div>
              <p className="landing-eyebrow">Waitlist</p>
              <h2 id="waitlist-title">Join the beta waitlist when Parkio invites early testers.</h2>
              <p>
                This beta-interest form validates input and shows loading, success, and error
                states while final waitlist API integration remains isolated.
              </p>
            </div>
            <WaitlistForm />
          </div>
        </section>
        <FAQSection />
      </main>
      <Footer />
    </div>
  );
}
