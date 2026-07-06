# Security Policy

## Supported versions

| Version | Supported |
|---------|-----------|
| `1.0.0-rc1` | Yes — hosted beta / release candidate |
| `< 1.0.0-rc1` | No |

## Reporting a vulnerability

**Do not** open public GitHub issues for security vulnerabilities.

Email the maintainers with:

- A clear description of the issue and impact
- Steps to reproduce (proof of concept if available)
- Affected component (gateway, auth, web, mobile, infrastructure)
- Your contact details for follow-up

We aim to acknowledge reports within **5 business days** and provide a remediation timeline for confirmed issues affecting hosted beta deployments.

## Security architecture (summary)

Parkio enforces security at multiple layers:

- **Edge:** Spring Cloud Gateway — RS256 JWT validation via JWKS, role-based route rules, rate limiting, CORS allow-list, internal `X-Gateway-Auth` secret
- **Auth:** Opaque refresh tokens (hashed at rest), rotation, family revocation, session epoch for access-token invalidation
- **Services:** Defense-in-depth RBAC re-checked in controllers and application services
- **Media:** ClamAV scanning, presigned URLs, upload size/type limits
- **Frontend:** HttpOnly refresh cookies (web), SecureStore (mobile), CSP/security headers (nginx), no tokens in localStorage
- **Supply chain:** gitleaks, Trivy, CycloneDX SBOMs, Dependabot (see `docs/operations/supply-chain-security.md`)

Full guidelines: [`docs/ai-context/07-security-guidelines.md`](docs/ai-context/07-security-guidelines.md)

## Secret handling

- Never commit real secrets. Preflight (`scripts/preflight-hosted-beta.sh`) blocks `CHANGE_ME` placeholders in production env files.
- Fixture files under `scripts/preflight-fixtures/` use fabricated values only.
- Rotate JWT keys, gateway secrets, and database passwords per environment.