# SEO Plan

This plan defines the initial search and social metadata strategy for `parkio.dev`.

## Primary SEO Goal

Make Parkio discoverable for branded and early product-intent searches while honestly reflecting its hosted-beta preparation stage.

## Title

Recommended:

> Parkio - Community-Powered Parking Intelligence

Alternate:

> Parkio - Parking Intelligence Powered by Real Drivers

## Meta Description

Recommended:

> Parkio helps drivers find, share, verify, and manage real-world parking availability. In release-candidate and hosted-beta preparation.

This description is intentionally stage-aware and should not imply public launch.

## Primary Keywords

- Parkio
- parking intelligence
- community-powered parking
- parking availability
- parking verification
- parking beta
- Smart Return
- parking waitlist

## Secondary Keywords

- find parking
- share parking availability
- verify parking spots
- mobile parking platform
- privacy-conscious parking app
- hosted beta parking app

Avoid over-optimizing for broad high-competition terms like "parking app" without clearer launch evidence.

## Structured Data

Recommended future structured data:

- `Organization`
- `WebSite`
- `SoftwareApplication`
- `FAQPage`
- `BreadcrumbList`

Implementation notes:

- Use `SoftwareApplication` only with honest stage and availability fields.
- Do not include ratings, reviews, offers, aggregateRating, or installs unless real evidence exists.
- Use `FAQPage` only if FAQ content is visible on the page.

## OpenGraph

Required fields:

- `og:title`: Parkio - Community-Powered Parking Intelligence
- `og:description`: Parkio helps drivers find, share, verify, and manage real-world parking availability. In hosted-beta preparation.
- `og:url`: https://parkio.dev/
- `og:type`: website
- `og:image`: TBD

OpenGraph image direction:

- Use Parkio wordmark, product workflow preview, and calm parking signal graphics.
- Avoid dark maps, taxi yellow-black dominance, and fake screenshots that imply launch availability.

## Twitter Card

Recommended:

- `twitter:card`: summary_large_image
- `twitter:title`: Parkio - Community-Powered Parking Intelligence
- `twitter:description`: Parkio helps drivers find, share, verify, and manage real-world parking availability.
- `twitter:image`: TBD

## Robots

Initial public landing page:

> index, follow

Private or incomplete pages:

> noindex, nofollow

Use `noindex` for:

- Internal docs mirrors not intended for public search
- Unfinished beta flows
- Staging environments
- Admin or reviewer-only links

## Sitemap

Initial sitemap entries:

- `/`
- `/faq` if a dedicated FAQ page exists
- `/privacy` when published
- `/terms` when published
- `/beta` if the waitlist has a dedicated route

Do not include:

- Staging pages
- Draft docs
- Internal dashboards
- Private beta application admin screens

## Canonical URLs

Use:

- `https://parkio.dev/`

Avoid duplicate canonical roots across:

- `www.parkio.dev`
- temporary hosting URLs
- preview deployments

## Launch Metadata Checklist

- Title is present and under practical search-result length.
- Meta description is stage-aware.
- OpenGraph image exists and matches brand direction.
- Favicon exists at common sizes.
- Robots settings do not accidentally hide the public landing page.
- Sitemap is published only when routes are stable.
- No structured data includes fake ratings, reviews, pricing, installs, or availability.
- Privacy and terms links are either published or clearly marked TBD before public launch.

## Measurement Plan

Search metrics to monitor after launch:

- Branded impressions for "Parkio"
- Landing page clicks
- Waitlist conversion rate
- Documentation link clicks
- FAQ engagement

Current metrics:

> Not yet measured.
