#!/usr/bin/env python3
"""Unit tests for scripts/lib/rollback_schema_gate.py (CL-F12 F-INV-3, owner decision 2026-10-05).

The manifests are realistic. Their migrationVersions is this checkout's own list, as
parkio_migration_versions_json writes it into every deploy manifest. Their images map is the
services a rollback re-points, written the way parkio_write_manifest writes them. Each case edits
one thing.
"""
from __future__ import annotations

import copy
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts" / "lib"))
import rollback_schema_gate as gate  # noqa: E402


def checkout_migrations() -> dict:
    out = subprocess.run(["bash", "-c", "source scripts/lib/deploy-common.sh; parkio_migration_versions_json"],
                         cwd=ROOT, capture_output=True, text=True, check=True).stdout
    return json.loads(out)


MIGRATIONS = checkout_migrations()
SERVICES = sorted(MIGRATIONS)
TAG = "sha-" + "c" * 40


def manifest(git_sha: str, migrations: dict = None, images: list = None) -> dict:
    return {"schemaVersion": 1, "action": "deploy", "deploymentProfile": "invite-production",
            "gitSha": git_sha, "imageTag": TAG,
            "images": {s: f"parkio/{s}:{TAG}" for s in (SERVICES if images is None else images)},
            "migrationVersions": copy.deepcopy(MIGRATIONS if migrations is None else migrations)}


def latest(service: str) -> str:
    return max(MIGRATIONS[service], key=gate.version_key)


class Fixture(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)

    def write(self, name: str, content) -> str:
        path = Path(self.tmp.name) / name
        path.write_text(content if isinstance(content, str) else json.dumps(content), encoding="utf-8")
        return str(path)

    def run_gate(self, target, deployed):
        target_path = target if isinstance(target, str) else self.write("target.json", target)
        deployed_path = deployed if isinstance(deployed, str) else self.write("deployed.json", deployed)
        return gate.main(["--target", target_path, "--deployed", deployed_path])

    def assert_refused(self, target, deployed, text):
        with self.assertRaises(gate.Refused) as caught:
            gate.check(target, deployed)
        self.assertIn(text, str(caught.exception))


class RealisticData(unittest.TestCase):
    def test_this_checkout_records_versioned_scripts_for_the_services_with_migrations(self):
        self.assertIn("parking-service", MIGRATIONS)
        self.assertTrue(MIGRATIONS["parking-service"])
        self.assertTrue(all(gate.SCRIPT.fullmatch(s) for scripts in MIGRATIONS.values() for s in scripts))


class Compatible(Fixture):
    def test_the_same_migrations_are_compatible(self):
        self.assertEqual(self.run_gate(manifest("a" * 40), manifest("b" * 40)), 0)

    def test_a_target_that_knows_more_scripts_is_compatible(self):
        deployed = copy.deepcopy(MIGRATIONS)
        deployed["parking-service"].remove(latest("parking-service"))
        self.assertIn("compatible", gate.check(manifest("a" * 40), manifest("b" * 40, deployed)))

    def test_a_service_the_rollback_does_not_re_point_is_not_compared(self):
        # hosted-beta: a digest-pinned service keeps its image, so the target does not list it.
        deployed = copy.deepcopy(MIGRATIONS)
        deployed["gateway-service"].append("V999__only_the_pin_has_this.sql")
        target = manifest("a" * 40, images=[s for s in SERVICES if s != "gateway-service"])
        self.assertIn("compatible", gate.check(target, manifest("b" * 40, deployed)))


class SchemaAhead(Fixture):
    def test_a_deployed_script_the_target_lacks_is_refused_and_named(self):
        target = copy.deepcopy(MIGRATIONS)
        newest = latest("parking-service")
        target["parking-service"].remove(newest)
        self.assertEqual(self.run_gate(manifest("a" * 40, target), manifest("b" * 40)), 3)
        self.assert_refused(manifest("a" * 40, target), manifest("b" * 40),
                            f"the live schema is ahead of the rollback target (parking-service: {newest})")

    def test_a_higher_maximum_version_does_not_make_up_for_a_missing_script(self):
        deployed = {s: [] for s in SERVICES}
        deployed["parking-service"] = ["V1__base.sql", "V3__deployed_only.sql"]
        target = {s: [] for s in SERVICES}
        target["parking-service"] = ["V1__base.sql", "V2__target.sql", "V9__target_later.sql"]
        self.assert_refused(manifest("a" * 40, target), manifest("b" * 40, deployed), "parking-service: V3__deployed_only.sql")

    def test_a_script_that_kept_its_version_under_another_name_is_different(self):
        deployed = {s: [] for s in SERVICES}
        deployed["auth-service"] = ["V1__base.sql", "V2__add_index.sql"]
        target = {s: [] for s in SERVICES}
        target["auth-service"] = ["V1__base.sql", "V2__add_other_index.sql"]
        self.assert_refused(manifest("a" * 40, target), manifest("b" * 40, deployed), "auth-service: V2__add_index.sql")

    def test_several_services_ahead_are_all_named(self):
        deployed = copy.deepcopy(MIGRATIONS)
        deployed["media-service"].append("V900__x.sql")
        deployed["user-service"].append("V901__y.sql")
        self.assert_refused(manifest("a" * 40), manifest("b" * 40, deployed), "media-service: V900__x.sql; user-service: V901__y.sql")


class MissingOrUnreadable(Fixture):
    def test_a_missing_deployed_manifest_is_refused(self):
        missing = str(Path(self.tmp.name) / "absent.json")
        self.assertEqual(self.run_gate(manifest("a" * 40), missing), 3)

    def test_an_unreadable_manifest_is_refused(self):
        self.assertEqual(self.run_gate(self.write("t.json", "{not json"), manifest("b" * 40)), 3)
        self.assertEqual(self.run_gate(manifest("a" * 40), self.write("d.json", "[1, 2]")), 3)

    def test_a_manifest_without_migration_versions_is_refused(self):
        bare = manifest("a" * 40)
        del bare["migrationVersions"]
        self.assert_refused(bare, manifest("b" * 40), "the target manifest records no migrationVersions")
        self.assert_refused(manifest("a" * 40), bare, "the deployed release's manifest records no migrationVersions")
        legacy = manifest("b" * 40)
        legacy["migrations"] = legacy.pop("migrationVersions")  # the key the old gate read
        self.assert_refused(manifest("a" * 40), legacy, "records no migrationVersions")

    def test_a_re_pointed_service_missing_from_either_list_is_refused(self):
        partial = copy.deepcopy(MIGRATIONS)
        del partial["parking-service"]
        self.assert_refused(manifest("a" * 40), manifest("b" * 40, partial),
                            "the deployed release's manifest records no migrationVersions for parking-service")
        self.assert_refused(manifest("a" * 40, partial), manifest("b" * 40),
                            "the target manifest records no migrationVersions for parking-service")

    def test_a_target_without_images_is_refused(self):
        target = manifest("a" * 40)
        target["images"] = {}
        self.assert_refused(target, manifest("b" * 40), "the target manifest records no images")


class Malformed(Fixture):
    def test_malformed_migration_versions_are_refused(self):
        cases = {
            "a list instead of an object": ["V1__a.sql"],
            "an empty object": {},
            "a service mapped to a string": {**MIGRATIONS, "parking-service": "V1__a.sql"},
            "a non-string script": {**MIGRATIONS, "parking-service": [1]},
            "a name that is not a V-script": {**MIGRATIONS, "parking-service": ["init.sql"]},
            "a repeatable script": {**MIGRATIONS, "parking-service": ["R__view.sql"]},
            "a path": {**MIGRATIONS, "parking-service": ["db/V1__a.sql"]},
            "a repeated script": {**MIGRATIONS, "parking-service": ["V1__a.sql", "V1__a.sql"]},
            "two scripts with one version": {**MIGRATIONS, "parking-service": ["V3__a.sql", "V3__b.sql"]},
            "one version written two ways": {**MIGRATIONS, "parking-service": ["V1.1__a.sql", "V1_1__b.sql"]},
        }
        for name, value in cases.items():
            with self.subTest(name):
                self.assertEqual(self.run_gate(manifest("a" * 40), manifest("b" * 40, value)), 3)
                self.assertEqual(self.run_gate(manifest("a" * 40, value), manifest("b" * 40)), 3)


if __name__ == "__main__":
    unittest.main(verbosity=1)
