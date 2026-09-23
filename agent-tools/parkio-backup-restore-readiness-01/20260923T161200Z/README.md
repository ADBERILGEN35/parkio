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
