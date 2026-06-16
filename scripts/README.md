# scripts

Developer and CI helper scripts.

| Script                | Purpose                                             |
|-----------------------|-----------------------------------------------------|
| `build-all.sh`        | Build and test every service via the Gradle wrapper.|
| `run-service.sh`      | Run a single service locally (`run-service.sh auth-service`). |
| `backup-databases.sh` | Dump every service DB (`pg_dump` via `docker exec`); optional AES-256 + offsite upload. |
| `restore-database.sh` | Restore ONE service DB from a dump (destructive; guarded by a confirmation prompt). |
| `verify-backup.sh`    | Prove a dump restores cleanly into a disposable temp DB (live data untouched). |
| `restore-drill.sh`    | End-to-end restore drill: seed canary → backup → restore → assert data **and** parking's PostGIS objects survive. Runs in CI (`backup-restore-drill.yml`). |

All scripts assume they are run from the repository root or use paths relative
to it.

The backup/restore/drill scripts require the `parkio-postgres-*` containers to be
running and load DB credentials from `PARKIO_ENV_FILE` (e.g. `docker/.env`). See
[`docker/README.md`](../docker/README.md) §"Backups & restore" for the full runbook.
