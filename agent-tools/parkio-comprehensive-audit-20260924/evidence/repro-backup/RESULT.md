# F-02 yeniden üretim — MinIO aynası başarısızken COMPLETE + offsite

Ops inceleme ajanı tarafından, `scripts/backup-hosted-beta.sh` ve `scripts/lib/*` kopyası üzerinde
(scratch `ops/h1`) sahte `pg_dump` çıktıları ve mirror ortasında başarısız olan bir MinIO adımıyla çalıştırıldı.
Gerçek veritabanı, gerçek MinIO veya gerçek offsite hedefi kullanılmadı (`mc` stub'ı yalnızca çağrıyı loglar).

Gözlem:
- Çıkış kodu 1, manifest `"minioOk": 0` (bkz. `h1-backup-manifest.json`)
- Yine de stamp içinde `COMPLETE`, `SHA256SUMS`, `minio.tar.gz.enc` oluştu (`h1-stamp-listing.txt`)
- Offsite: `mc mirror …` ve `mc cp … /COMPLETE` çağrıldı (`h1-offsite-calls.log`)

Kök neden (elle doğrulandı): `scripts/backup-hosted-beta.sh:104` → `parkio_backup_allow_complete "${DEST_DIR}" "${DB_FAILED}"`;
`scripts/lib/backup-common.sh:403-428` yalnızca DB hatası + ledger kontrol ediyor, `MINIO_OK` parametresi yok.

h2 (tekil `scripts/backup-databases.sh`, BACKUP_PRODUCTION_MODE=1, tüm dump'lar + ledger başarısız):
"11 failure(s)", EXIT=1, yine de `COMPLETE` + offsite (`h2-mc.log`). Kök neden `backup-databases.sh:131-134`
`failures` sayacına bakmadan `write_stamp_integrity && offsite_upload` çağırıyor. Not: runbook bu betiğin tek başına
cron'lanmamasını söylüyor (`docs/operations/backup-runbook.md:42`); üretim yolu orkestratör.
