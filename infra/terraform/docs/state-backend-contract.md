# State / backend contract (PP-01B-IAC-01)

## Rules

1. **Local state** during offline authoring is allowed and **must be gitignored**
   (`*.tfstate`, `*.tfstate.*`, `.terraform/`).
2. **Remote backend** (encrypted + locking + environment-separated) is
   **required before any shared apply**. Backend configuration is supplied
   **externally** (partial backend config / CI secrets) — never committed with
   credentials.
3. **No production backend** is configured in this package.
4. **No state files** are committed.
5. Terraform state may contain sensitive values after apply — treat state as
   secret material; never paste into tickets/chat.
6. Sandbox cleanup remains the applying operator's responsibility
   (`sandbox_cleanup_deadline` input when apply is later authorized).

## What this package does not do

- Does not provision Azure resources
- Does not create remote state storage
- Does not authorize `terraform apply` / `destroy`
- Does not start PP-01C JDBC binding
