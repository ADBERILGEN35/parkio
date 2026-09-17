# 18 — Next Agent Handoff

## Bu paketin bıraktığı durum

Kaynak: provider-agnostic Public Explore + reviewed IZUM/ISPARK policy.  
Production mutasyon: **0**.  
Sonraki: CI green → `READY_FOR_CIVO_ROLLOUT` → **explicit human approval**.

## Operatör onayı sonrası sıra

1. Civo read-only preflight + backup gate
2. Stage 1: code, allowlist IZUM
3. Stage 2: ISPARK ingest only + manual sync
4. Kadıköy internal spatial
5. Stage 3: allowlist IZUM,ISPARK
6. Public/API/web smokes
7. Scheduler ayrı paket

## Yapma

- Future provider auto-enable
- Force-push / merge PR #44 / master touch
- `docker compose down -v`
- Secret dump
- PA-06 “fırsat” fix
