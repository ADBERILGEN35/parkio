#!/usr/bin/env python3
"""Secret-free profile of a plain pg_dump stream, and restore parity checks.

Subcommands:
  profile            read plain SQL on stdin; print JSON with server/pg_dump
                     versions, extensions, table count, per-table COPY row counts
                     and the Flyway head. Row contents are never emitted, except
                     the version/success columns of flyway_schema_history.
                     grantRoles lists roles that must exist before restore.
  count-sql PROFILE  print one read-only SQL query that counts the same tables
                     in a restored database (output: table|count per line).
  compare PROFILE COUNTS
                     compare a profile with `psql -At -F'|'` output of count-sql.
                     Application tables must match exactly. PostGIS / Tiger /
                     Topology catalogs that CREATE EXTENSION reseeds are recorded
                     in extensionCatalogsExcluded when the dump COPY is empty
                     (pg_dump of unmodified extension members). They are not
                     treated as application-data mismatches. Customized catalog
                     rows (dump count > 0) are still compared.

Typical drill use (isolated host only):
  openssl enc -d ... < auth.sql.gz.enc | gunzip | restore-dump-profile.py profile > auth.profile.json

Exit: 0 = PASS, 1 = FAIL, 2 = usage error.
"""
import json
import re
import sys

COPY_RE = re.compile(rb'^COPY\s+((?:"[^"]+"|[\w$]+)(?:\.(?:"[^"]+"|[\w$]+))?)\s*\(([^)]*)\)\s+FROM\s+stdin;')
EXT_RE = re.compile(rb"^CREATE EXTENSION (?:IF NOT EXISTS )?(\"?[\w-]+\"?)")
TABLE_RE = re.compile(rb"^CREATE (?:UNLOGGED )?TABLE ")
SERVER_RE = re.compile(rb"^-- Dumped from database version (\S+)")
DUMPER_RE = re.compile(rb"^-- Dumped by pg_dump version (\S+)")
GRANT_RE = re.compile(rb"^GRANT .+ TO (.+);$")
FLYWAY_TABLE = "flyway_schema_history"
SAFE_NAME_RE = re.compile(r'^(?:"[^"]+"|[\w$]+)(?:\.(?:"[^"]+"|[\w$]+))?$')
# CREATE EXTENSION postgis / postgis_tiger_geocoder / postgis_topology reseeds these.
# pg_dump of unmodified extension members emits COPY with 0 rows (CI 35887774967).
EXTENSION_CATALOG_SCHEMAS = frozenset({"tiger", "tiger_data", "topology"})
EXTENSION_CATALOG_EXCLUDE_REASON = (
    "CREATE EXTENSION reseeds PostGIS/Tiger/Topology catalogs; "
    "pg_dump of unmodified extension members emits an empty COPY"
)


def _version_key(value):
    return tuple(int(p) for p in re.findall(r"\d+", value or ""))


def profile(stream):
    result = {"serverVersion": None, "pgDumpVersion": None, "extensions": [],
              "createTableCount": 0, "rowCounts": {}, "flywayHead": None,
              "flywayFailedRows": 0, "grantRoles": [], "restrictCommands": False}
    copying = None
    flyway_cols = None
    for raw in stream:
        line = raw.rstrip(b"\r\n")
        if copying is not None:
            if line == b"\\.":
                copying = None
                flyway_cols = None
                continue
            result["rowCounts"][copying] += 1
            if flyway_cols:
                fields = line.split(b"\t")
                version = fields[flyway_cols[0]].decode("utf-8", "replace")
                success = fields[flyway_cols[1]] == b"t"
                if version != "\\N" and success:
                    if _version_key(version) > _version_key(result["flywayHead"]):
                        result["flywayHead"] = version
                elif not success:
                    result["flywayFailedRows"] += 1
            continue
        match = COPY_RE.match(line)
        if match:
            copying = match.group(1).decode("utf-8", "replace")
            result["rowCounts"].setdefault(copying, 0)
            if copying.split(".")[-1].strip('"') == FLYWAY_TABLE:
                cols = [c.strip().strip('"') for c in match.group(2).decode().split(",")]
                if "version" in cols and "success" in cols:
                    flyway_cols = (cols.index("version"), cols.index("success"))
            continue
        if result["serverVersion"] is None and (m := SERVER_RE.match(line)):
            result["serverVersion"] = m.group(1).decode()
        elif result["pgDumpVersion"] is None and (m := DUMPER_RE.match(line)):
            result["pgDumpVersion"] = m.group(1).decode()
        elif m := EXT_RE.match(line):
            name = m.group(1).decode().strip('"')
            if name not in result["extensions"]:
                result["extensions"].append(name)
        elif TABLE_RE.match(line):
            result["createTableCount"] += 1
        elif m := GRANT_RE.match(line):
            for role in m.group(1).decode("utf-8", "replace").split(","):
                role = role.strip().strip('"')
                if role and role.upper() != "PUBLIC" and role not in result["grantRoles"]:
                    result["grantRoles"].append(role)
        elif line.startswith(b"\\restrict") or line.startswith(b"\\unrestrict"):
            result["restrictCommands"] = True
    if copying is not None:
        result["truncated"] = True
    return result


def _unquote_ident(part):
    part = part.strip()
    if len(part) >= 2 and part[0] == '"' and part[-1] == '"':
        return part[1:-1].replace('""', '"')
    return part


def relation_parts(name):
    if "." in name:
        schema, table = name.split(".", 1)
        return _unquote_ident(schema), _unquote_ident(table)
    return None, _unquote_ident(name)


def is_extension_catalog(name):
    schema, table = relation_parts(name)
    if table == "spatial_ref_sys":
        return True
    return schema in EXTENSION_CATALOG_SCHEMAS


def count_sql(prof):
    names = sorted(prof["rowCounts"])
    for name in names:
        if not SAFE_NAME_RE.match(name):
            raise ValueError(f"refusing unexpected table identifier: {name!r}")
    if not names:
        return "SELECT 'none'::text, 0 WHERE false;"
    parts = [f"SELECT '{n.replace(chr(39), chr(39) * 2)}'::text AS t, count(*) FROM {n}" for n in names]
    return "\nUNION ALL\n".join(parts) + ";"


def compare(prof, counts_text):
    restored = {}
    for line in counts_text.splitlines():
        if "|" in line:
            name, value = line.rsplit("|", 1)
            restored[name.strip()] = int(value.strip())
    mismatches = {}
    excluded = {}
    for name, dump_count in prof["rowCounts"].items():
        restored_count = restored.get(name)
        if restored_count == dump_count:
            continue
        if is_extension_catalog(name) and dump_count == 0:
            excluded[name] = {
                "dump": dump_count,
                "restored": restored_count,
                "reason": EXTENSION_CATALOG_EXCLUDE_REASON,
            }
            continue
        mismatches[name] = {"dump": dump_count, "restored": restored_count}
    ok = not mismatches and not prof.get("truncated")
    result = {"verdict": "PASS" if ok else "FAIL", "tables": len(prof["rowCounts"]),
              "rowsInDump": sum(prof["rowCounts"].values()),
              "truncatedDump": bool(prof.get("truncated")), "mismatches": mismatches}
    if excluded:
        result["extensionCatalogsExcluded"] = excluded
    return result


def main(argv):
    if not argv:
        sys.stderr.write(__doc__)
        return 2
    command = argv[0]
    if command == "profile" and len(argv) == 1:
        out = profile(sys.stdin.buffer)
        json.dump(out, sys.stdout, indent=2, sort_keys=True)
        sys.stdout.write("\n")
        return 1 if out.get("truncated") else 0
    if command == "count-sql" and len(argv) == 2:
        with open(argv[1]) as handle:
            print(count_sql(json.load(handle)))
        return 0
    if command == "compare" and len(argv) == 3:
        with open(argv[1]) as handle:
            prof = json.load(handle)
        with open(argv[2]) as handle:
            result = compare(prof, handle.read())
        json.dump(result, sys.stdout, indent=2, sort_keys=True)
        sys.stdout.write("\n")
        return 0 if result["verdict"] == "PASS" else 1
    sys.stderr.write(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
