import { LandingIcon } from './LandingIcon';
import { workflowSteps } from './landingContent';

export function ProductWorkflowPreview() {
  return (
    <div className="landing-preview" aria-label="Parkio product workflow preview">
      <div className="landing-preview__chrome">
        <span />
        <span />
        <span />
      </div>
      <div className="landing-preview__map" aria-hidden="true">
        <div className="landing-preview__route" />
        <div className="landing-preview__spot landing-preview__spot--one">P</div>
        <div className="landing-preview__spot landing-preview__spot--two">
          <LandingIcon name="verified" className="landing-preview__spot-icon" />
        </div>
        <div className="landing-preview__spot landing-preview__spot--three">
          <LandingIcon name="notifications_active" className="landing-preview__spot-icon" />
        </div>
      </div>
      <div className="landing-preview__panel">
        <p className="landing-eyebrow">Product workflow</p>
        <h2>Find, share, verify, return.</h2>
        <p>
          This preview shows the public product loop without claiming live city coverage or beta
          usage. Real product screenshots should replace it after hosted-beta capture.
        </p>
      </div>
      <ol className="landing-preview__steps" aria-label="Parkio workflow steps">
        {workflowSteps.map((step) => (
          <li key={step.title}>
            <LandingIcon name={step.icon} className="landing-preview__step-icon" />
            <span>{step.title}</span>
          </li>
        ))}
      </ol>
    </div>
  );
}
