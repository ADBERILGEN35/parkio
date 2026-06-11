import { Card, PageShell } from '@parkio/ui';

interface PlaceholderPageProps {
  title: string;
  description: string;
}

export function PlaceholderPage({ title, description }: PlaceholderPageProps) {
  return (
    <PageShell title={title}>
      <Card>
        <p style={{ margin: 0, color: '#64748b' }}>{description}</p>
      </Card>
    </PageShell>
  );
}
