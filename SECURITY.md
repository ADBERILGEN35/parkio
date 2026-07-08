# Security Policy

Parkio is preparing for hosted beta. This policy explains how to report security
issues and what parts of the repository are currently supported.

## Supported Versions

| Version | Supported |
|---------|-----------|
| `v1.0.0-rc1` | Yes - hosted-beta release candidate |
| `< 1.0.0-rc1` | No |

Public production is not supported by this release line. See
[Known Issues](docs/releases/KNOWN-ISSUES.md) for current production blockers.

## Reporting a Vulnerability

**Do not** open public GitHub issues for security vulnerabilities.

Use GitHub private vulnerability reporting if it is enabled for the repository.
If it is not enabled, contact the repository owner privately through their GitHub
profile before sharing exploit details in a public channel.

Include:

- A clear description of the issue and potential impact.
- Steps to reproduce, including proof of concept when safe to share privately.
- Affected component: gateway, auth, service, web, mobile, Docker, CI, or docs.
- Whether the issue affects local development, hosted beta, or both.
- Any logs, screenshots, or traces with secrets and personal data redacted.

The maintainer aims to acknowledge security reports within 5 business days.
Remediation timelines depend on severity, exploitability, and whether a hosted
beta deployment is affected. Parkio does not currently operate a paid bug bounty.

## Security Architecture Summary

Parkio uses defense in depth across the application and operator surface:

- **Edge:** Spring Cloud Gateway, RS256 JWT validation, JWKS, route rules,
  rate limiting, CORS allow-list, and internal gateway secret.
- **Auth:** Opaque refresh tokens hashed at rest, rotation, family revocation,
  and session epoch invalidation.
- **Services:** Defense-in-depth authorization checks inside controllers and
  application services.
- **Media:** Presigned upload flow, ClamAV scanning, file type/size limits, and
  signed access URLs.
- **Frontend:** HttpOnly refresh cookies for web, SecureStore for mobile,
  security headers, and no access tokens in localStorage.
- **Supply chain:** gitleaks, Trivy, CycloneDX SBOMs, Dependabot, and CI gates.

Useful references:

- [Security Guidelines](docs/ai-context/07-security-guidelines.md)
- [Security Boundaries](docs/operations/security-boundaries.md)
- [Supply Chain Security](docs/operations/supply-chain-security.md)
- [Production Readiness](docs/architecture/production-readiness.md)

## Secret Handling

- Never commit real secrets, tokens, private keys, production env files, or raw
  user data.
- `scripts/preflight-hosted-beta.sh` blocks known placeholder values in hosted
  beta env files.
- Fixture files under `scripts/preflight-fixtures/` use fabricated values only.
- JWT keys, gateway secrets, database passwords, Redis credentials, and object
  storage credentials must be unique per environment and rotated when exposed.

## Out of Scope for This Policy

Use normal issues or support channels for:

- Documentation typos.
- Feature requests.
- Local setup help without a security impact.
- Reports that require access to systems not operated by the maintainer.
