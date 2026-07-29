# Security Operational Readiness

Secrets: PARKIO_GATEWAY_INTERNAL_SECRET required; no defaults in repo.

JWT: auth-service JWKS; refresh hashed.

Scanning: security-ci.yml, supply-chain.yml.

Local Docker secrets in .env (not committed). Production secrets manager: PRODUCT/INFRA INPUT REQUIRED.

See security-boundaries.md and supply-chain-security.md.