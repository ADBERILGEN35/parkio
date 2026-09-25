# 15 — Rollout ve Rollback Planı

## Planlanan Stage’ler (onay sonrası)

1. Code deploy, public allowlist **IZUM only**
2. ISPARK ingest ON, scheduler OFF, public hâlâ IZUM
3. Manual ISPARK sync + DB/Kadıköy internal check
4. Public allowlist `IZUM,ISPARK`
5. API/web/mobile smoke
6. Scheduler ayrı karar

## Rollback

- Migration 0 → DB restore gerekmez
- Publication geri alma: allowlist → `IZUM`
- Code fault: önceki Civo SHA image
- ISPARK satırları rutin silinmez

## Compose

`docker compose down -v` **YASAK**. Volume prune yok. Hostinger/Caddy dokunulmaz (kanıt yoksa).
