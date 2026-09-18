-- PROD-DEPLOY-01A-R8.6 — census of every object in the `public` schema, each POSITIVELY
-- attributed to what put it there.
--
-- Emits one row per object:  <objkind>|<objname>|<attribution>
-- where attribution is 'extension:<name>', 'flyway', or 'UNATTRIBUTED'.
--
-- WHY THIS IS NOT A pg_class JOIN
--
-- R8.5's preparation tool asked only whether a pg_class row had a deptype='e' dependency. That
-- misses two whole classes of extension-owned object and produced a false BLOCKED against live
-- invite-production:
--
--   * COMPOSITE TYPES. PostGIS installs `geometry_dump` and `valid_detail` into public. Their
--     pg_class rows carry no extension dependency at all — membership is recorded against the
--     corresponding pg_type row, so the relation must be traced pg_class -> reltype -> pg_depend.
--   * INDEXES and dependent relations. `spatial_ref_sys_pkey` belongs to the extension only by way
--     of the table it indexes; its own dependency is 'i'/'a' on that table, never 'e'.
--
-- Attribution is therefore computed in two stages: a DIRECT set (relation-, type- or Flyway-owned)
-- and a DERIVED set that inherits from it (indexes, identity/serial sequences, other internal or
-- automatic dependants).
--
-- FAIL-CLOSED: attribution is positive only. Anything this query cannot tie to an extension or to
-- Flyway comes back UNATTRIBUTED — including a composite type, enum, domain, view or sequence that
-- merely looks PostGIS-ish. relkind='c' is never blanket-ignored.
WITH public_rel AS (
    SELECT c.oid, c.relname::text AS relname, c.relkind::text AS relkind, c.reltype
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public'
),
rel_ext AS (
    -- Ordinary extension-owned relations: tables, views, sequences (e.g. spatial_ref_sys).
    SELECT r.oid, e.extname::text AS extname
    FROM public_rel r
    JOIN pg_depend d
      ON d.objid = r.oid AND d.classid = 'pg_class'::regclass AND d.deptype = 'e'
    JOIN pg_extension e ON e.oid = d.refobjid
),
type_ext AS (
    -- Composite types, whose membership lives on pg_type (geometry_dump, valid_detail).
    SELECT r.oid, e.extname::text AS extname
    FROM public_rel r
    JOIN pg_depend d
      ON d.objid = r.reltype AND d.classid = 'pg_type'::regclass AND d.deptype = 'e'
    JOIN pg_extension e ON e.oid = d.refobjid
    WHERE r.reltype <> 0
),
flyway_rel AS (
    SELECT r.oid FROM public_rel r WHERE r.relname = 'flyway_schema_history'
),
direct AS (
    SELECT oid, 'extension:' || extname AS attribution FROM rel_ext
    UNION
    SELECT oid, 'extension:' || extname FROM type_ext
    UNION
    SELECT oid, 'flyway' FROM flyway_rel
),
derived AS (
    -- Indexes inherit from the relation they index (spatial_ref_sys_pkey,
    -- flyway_schema_history_pk, flyway_schema_history_s_idx).
    SELECT r.oid, d0.attribution
    FROM public_rel r
    JOIN pg_index ix ON ix.indexrelid = r.oid
    JOIN direct d0 ON d0.oid = ix.indrelid
    UNION
    -- Identity/serial sequences and other internal or automatic dependants.
    SELECT r.oid, d0.attribution
    FROM public_rel r
    JOIN pg_depend dep
      ON dep.objid = r.oid AND dep.classid = 'pg_class'::regclass
     AND dep.refclassid = 'pg_class'::regclass AND dep.deptype IN ('a', 'i')
    JOIN direct d0 ON d0.oid = dep.refobjid
),
attributed AS (
    SELECT oid, attribution FROM direct
    UNION
    SELECT oid, attribution FROM derived
)
SELECT r.relkind AS objkind,
       r.relname AS objname,
       coalesce(min(a.attribution), 'UNATTRIBUTED') AS attribution
  FROM public_rel r
  LEFT JOIN attributed a ON a.oid = r.oid
 GROUP BY r.relkind, r.relname

UNION ALL

-- Routines. Extension membership is recorded directly on pg_proc, so no tracing is needed.
SELECT 'proc', p.proname::text,
       coalesce('extension:' || e.extname::text, 'UNATTRIBUTED')
  FROM pg_proc p
  JOIN pg_namespace n ON n.oid = p.pronamespace
  LEFT JOIN pg_depend d
    ON d.objid = p.oid AND d.classid = 'pg_proc'::regclass AND d.deptype = 'e'
  LEFT JOIN pg_extension e ON e.oid = d.refobjid
 WHERE n.nspname = 'public'

UNION ALL

-- Standalone types: enums, domains and extension base types (geometry, geography).
-- Row types of relations are excluded (they appear above as relkind 'c'/'r'), as are array types.
SELECT 'type', t.typname::text,
       coalesce('extension:' || e.extname::text, 'UNATTRIBUTED')
  FROM pg_type t
  JOIN pg_namespace n ON n.oid = t.typnamespace
  LEFT JOIN pg_depend d
    ON d.objid = t.oid AND d.classid = 'pg_type'::regclass AND d.deptype = 'e'
  LEFT JOIN pg_extension e ON e.oid = d.refobjid
 WHERE n.nspname = 'public'
   AND t.typrelid = 0
   AND t.typcategory::text <> 'A'

 ORDER BY 3, 1, 2;
