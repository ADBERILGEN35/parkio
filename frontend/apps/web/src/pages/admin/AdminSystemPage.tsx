import { Card, PageShell, SoftBadge } from '@parkio/ui';
import { Link } from 'react-router-dom';
import { frontendConfig } from '@/config/env';

export function AdminSystemPage() {
  return (
    <PageShell title="System">
      <p className="mb-lg mt-0 text-body-md text-on-surface-variant">
        Deployment context for this Parkio web client.
      </p>
      <div className="flex flex-col gap-lg">
        <Card title="Environment">
          <dl className="grid grid-cols-1 gap-sm sm:grid-cols-2">
            <div>
              <dt className="text-label-md text-on-surface-variant">App environment</dt>
              <dd className="m-0 mt-xs">
                <SoftBadge tone={frontendConfig.isProductionLike ? 'warning' : 'neutral'}>
                  {frontendConfig.appEnv}
                </SoftBadge>
              </dd>
            </div>
            <div>
              <dt className="text-label-md text-on-surface-variant">API base</dt>
              <dd className="m-0 mt-xs break-all font-mono text-body-sm">{frontendConfig.apiBaseUrl}</dd>
            </div>
          </dl>
        </Card>

        <Card title="Deep infrastructure observability">
          <p className="m-0 text-body-md text-on-surface-variant">
            Prometheus, Grafana, Loki, and Tempo remain private on the hosted-beta VPS. Access them via
            SSH tunnel as documented in the operations runbooks — do not publish Grafana to the internet.
          </p>
          <p className="mt-sm mb-0 text-body-sm text-on-surface-variant">
            Example: <code>ssh -L 3000:127.0.0.1:3000 operator@vps</code> then open{' '}
            <code>http://localhost:3000</code>.
          </p>
        </Card>

        <Card title="Related admin surfaces">
          <ul className="m-0 list-disc space-y-xs pl-md text-body-md">
            <li>
              <Link to="/admin/moderation" className="text-primary">
                Moderation queue
              </Link>{' '}
              (moderation-service)
            </li>
            <li>
              <Link to="/admin/analytics" className="text-primary">
                Platform analytics
              </Link>{' '}
              (analytics-service aggregates)
            </li>
            <li>
              <Link to="/admin/audit" className="text-primary">
                Audit trail
              </Link>{' '}
              (auth-service admin audit events)
            </li>
          </ul>
        </Card>
      </div>
    </PageShell>
  );
}
