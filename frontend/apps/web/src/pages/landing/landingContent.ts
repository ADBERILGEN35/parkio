export const PARKIO_DOMAIN = 'parkio.dev';
export const CANONICAL_URL = 'https://parkio.dev/';
export const GITHUB_REPO_URL = 'https://github.com/ADBERILGEN35/parkio';
export const TECHNICAL_DOCS_URL = `${GITHUB_REPO_URL}/tree/main/docs/startup`;

export const PRODUCT_DESCRIPTION =
  'Parkio is a community-powered parking intelligence platform that helps drivers find, share, verify, and manage real-world parking availability.';

export const STAGE_DESCRIPTION =
  'Parkio is currently in release-candidate and hosted-beta preparation. The public production service is not live yet.';

export const navItems = [
  { href: '#product', label: 'Product' },
  { href: '#trust', label: 'Trust' },
  { href: '#technology', label: 'Technology' },
  { href: '#beta', label: 'Beta' },
  { href: '#faq', label: 'FAQ' },
] as const;

export const problemPoints = [
  {
    title: 'Availability is uncertain',
    body: 'Drivers often make parking decisions with incomplete or outdated information.',
    icon: 'travel_explore',
  },
  {
    title: 'Useful signals are fragmented',
    body: 'Fresh curbside knowledge usually stays with the people closest to the spot.',
    icon: 'hub',
  },
  {
    title: 'Maps do not fully solve curbside truth',
    body: 'Navigation tools are useful, but real-world availability changes faster than static map data.',
    icon: 'map',
  },
] as const;

export const workflowSteps = [
  {
    title: 'Find',
    body: 'Discover nearby parking signals from real-world community activity.',
    icon: 'search',
  },
  {
    title: 'Share',
    body: 'Contribute an available spot or report what changed at the curb.',
    icon: 'add_location_alt',
  },
  {
    title: 'Verify',
    body: 'Confirm whether a signal is still useful through claims, reports, and freshness cues.',
    icon: 'verified',
  },
  {
    title: 'Return',
    body: 'Use Smart Return and timely notifications to keep parking context manageable.',
    icon: 'notifications_active',
  },
] as const;

export const featureGroups = [
  {
    title: 'Spot sharing',
    body: 'Contribute real availability signals without turning the product into a generic map clone.',
    icon: 'local_parking',
  },
  {
    title: 'Community verification',
    body: 'Keep parking intelligence fresh through verification, claims, and reports.',
    icon: 'fact_check',
  },
  {
    title: 'Smart Return',
    body: 'Help drivers manage return context after parking without overstating automation.',
    icon: 'keyboard_return',
  },
  {
    title: 'Trust and moderation',
    body: 'Use reports, status labels, and moderation paths to keep a focused beta useful.',
    icon: 'shield',
  },
  {
    title: 'Mobile-first surfaces',
    body: 'Prioritize parking decisions from the phone while keeping web access useful for review flows.',
    icon: 'phone_iphone',
  },
  {
    title: 'Hosted-beta operations',
    body: 'Make deployment, observability, and public-production blockers visible before launch.',
    icon: 'monitoring',
  },
] as const;

export const trustItems = [
  {
    title: 'Privacy-conscious by design',
    body: 'The public beta must document data collection before launch. Parkio should help with parking decisions, not sell movement as inventory.',
    icon: 'lock',
  },
  {
    title: 'Community trust signals',
    body: 'Spot status should be understandable through freshness, verification, claims, and reports.',
    icon: 'groups',
  },
  {
    title: 'Honest beta boundaries',
    body: 'Supported areas, invite timing, cohort size, and public legal URLs remain TBD until they are real.',
    icon: 'info',
  },
] as const;

export const technologyPoints = [
  'Release-candidate documentation and certification evidence are available in the repository.',
  'The product includes mobile-first and web surfaces, with backend services planned for hosted-beta operation.',
  'Operational planning covers observability, infrastructure readiness, and public-production blockers.',
] as const;

export const betaFacts = [
  { label: 'Current stage', value: 'Hosted-beta preparation' },
  { label: 'Public production', value: 'Not live yet' },
  { label: 'Supported areas', value: 'TBD' },
  { label: 'Current metrics', value: 'Not yet measured' },
] as const;

export const faqItems = [
  {
    question: 'What is Parkio?',
    answer: PRODUCT_DESCRIPTION,
  },
  {
    question: 'Is Parkio live?',
    answer:
      'Not yet. Parkio is in release-candidate and hosted-beta preparation. The public production service is not live yet.',
  },
  {
    question: 'What cities are supported?',
    answer: 'Supported beta areas are TBD. The landing page should not list cities until they are confirmed.',
  },
  {
    question: 'How does spot verification work?',
    answer:
      'Drivers can share, verify, claim, or report parking signals. The goal is fresher community intelligence, not a guarantee that every spot remains available.',
  },
  {
    question: 'Does Parkio track my location?',
    answer:
      'Parkio is designed to be privacy-conscious. The beta privacy page explains current waitlist data use and deletion contact handling.',
  },
  {
    question: 'What is Smart Return?',
    answer:
      'Smart Return helps drivers manage return context and parking reminders after arrival. It should be treated as beta functionality, not full automation.',
  },
  {
    question: 'Is Parkio free?',
    answer: 'Beta monetization is not active. Future pricing is TBD.',
  },
  {
    question: 'Can I delete my data?',
    answer: 'For beta waitlist data, contact privacy@parkio.dev. During beta, deletion and support handling may be manual.',
  },
  {
    question: 'Is Parkio public-production ready?',
    answer:
      'No. Parkio is technically mature for hosted-beta preparation, but public production still requires deployment, operating history, privacy/legal publication, support processes, and real-world beta validation.',
  },
] as const;
