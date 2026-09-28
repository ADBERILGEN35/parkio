# evidence/: 2026-09-28 yeniden denetim kanıtları

Tüm dinamik testler `2877ec81`'in `git archive` kopyasında, sentetik verilerle ve stub docker/psql/mc ile çalıştırıldı. Hiçbir gerçek servise, registry'ye veya bildirim kanalına dokunulmadı.

| Dizin | Dosyalar | Komut |
|---|---|---|
| `f01-gateway-recheck/` | `AuditHeadBypassReproTest.java`, `RESULT.md` | `./gradlew :services:gateway-service:test --tests '*AuditHeadBypassReproTest*' --no-parallel -i` |
| `f02-backup-gate/` | `test-backup-complete-gate.txt` | `bash scripts/test-backup-complete-gate.sh` (depodaki test; 40/40 PASS) |
| `f03-restore/` | `test-restore-safe-preflight.txt` | `bash scripts/test-restore-safe-preflight.sh` (25/25 PASS) |
| `f03-restore/` | `audit-f03-isolated-flag.sh`, `isolated-flag-output.txt` | `bash scripts/audit-f03-isolated-flag.sh`. Depo testinin fixture kurulumu + iki deneme; kopyada `scripts/` altına konmalı (N-01). |
| `f09-slack-worker/` | `output.txt` | `cd <kopya>/scripts && python3 poison_probe.py && python3 poison_worker.py <scratch>`. Betikler `../../parkio-comprehensive-audit-20260924/evidence/repro-slack-worker/` altında. |
| `codeql/` | `javascript-typescript-results.txt` | `codeql database create --language=javascript-typescript --build-mode=none` + `database analyze … javascript-code-scanning.qls` (CodeQL CLI 2.27.1) |
| `codeql/` | `java-kotlin-results.txt` | `codeql database create --language=java-kotlin --command='./gradlew … compileJava compileTestJava'` + `java-code-scanning.qls` |
