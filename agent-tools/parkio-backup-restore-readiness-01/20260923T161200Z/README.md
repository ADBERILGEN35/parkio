# BRR-01 continuation (2026-09-23T161200Z)

Cursor took over draft PR #94 after Claude hit its usage limit.
No Claude Code process or session continuation was found for this worktree.
The WSL gitdir pointer was remapped to the Windows worktree path so git works;
uncommitted documentation from Claude was preserved.

## Verified CI failure (do not strip)

Run 35883781803, merge SHA 29bbbdb0, step Print synthetic restore errors:

    == auth.restore.err
    invalid command \\restrict

Dump-client: floating postgres:16-alpine (pg_dump emits \\restrict from 16.10+).
Restore-client: psql inside postgis/postgis:16-3.4 (older, rejects the command).
Target-server: postgis/postgis:16-3.4. PostGIS: image 16-3.4.

## Change in this continuation

Identified restore client postgres:16.10 on rd-net. Compatibility preflight
records dump-client, restore-client, target-server and PostGIS. Dumps are
not rewritten. Mock CI canary drill is unchanged.

No production access, no real backup download or restore.

## Follow-up CI 35887774967 on 0431dfd9

Restrict is resolved. `parking.compat.json` PASS. Restore-client 16.10,
target-server 16.4, PostGIS 3.4.3. Failure is `row-count parity parking`:

    public.spatial_ref_sys dump=0 restored=8500
    tiger.pagc_gaz dump=0 restored=835
    tiger.pagc_lex dump=0 restored=2938
    tiger.pagc_rules dump=0 restored=4354

`CREATE EXTENSION` reseeds those catalogs after pg_dump emits an empty COPY
for unmodified extension members. Application tables were not the mismatch.
Parity now records them in `extensionCatalogsExcluded` when dump count is 0.
Customized catalog rows (dump > 0) and application tables still fail closed.

Artifact: `ci-35887774967/` (secret-free).
