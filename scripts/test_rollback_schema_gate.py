#!/usr/bin/env python3
"""Unit tests for scripts/lib/rollback_schema_gate.py (CL-F12 F-INV-3, owner decision 2026-10-05).

The manifests are realistic. Their migrationVersions is this checkout's own list, as
parkio_migration_versions_json writes it into every deploy manifest. Their images map is the
services a rollback re-points, written the way parkio_write_manifest writes them. The hosted-beta
image plan has the lines parkio_hosted_beta_image_plan prints, with this checkout's own digest pins
from docker/compose.production.files. Each case edits one thing.
"""
from __future__ import annotations

import copy
import json
import re
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


def checkout_app_services() -> list:
    out = subprocess.run(["bash", "-c", "source scripts/lib/deploy-common.sh; printf '%s\\n' \"${PARKIO_APP_SERVICES[@]}\""],
                         cwd=ROOT, capture_output=True, text=True, check=True).stdout
    return out.split()


def checkout_pins() -> dict:
    """{service: image} for every digest pin in the files docker/compose.production.files lists."""
    pins = {}
    for entry in (ROOT / "docker" / "compose.production.files").read_text(encoding="utf-8").splitlines():
        entry = entry.strip()
        if not entry or entry.startswith("#"):
            continue
        service = None
        for line in (ROOT / entry).read_text(encoding="utf-8").splitlines():
            key = re.fullmatch(r"  ([a-z][a-z0-9-]*):\s*", line)
            if key:
                service = key.group(1)
            image = re.fullmatch(r"    image:\s*(\S+@sha256:[0-9a-f]{64})\s*", line)
            if image and service:
                pins[service] = image.group(1)
    return pins


MIGRATIONS = checkout_migrations()
SERVICES = sorted(MIGRATIONS)
TAG = "sha-" + "c" * 40
APP_SERVICES = checkout_app_services()
PINS = checkout_pins()
BUILT = [s for s in APP_SERVICES if s not in PINS]
OTHER_DIGEST = "sha256:" + "0" * 64


def plan(pins: dict = None, built: list = None) -> str:
    """An image plan as parkio_hosted_beta_image_plan prints it, in PARKIO_APP_SERVICES order."""
    pins = PINS if pins is None else pins
    built = BUILT if built is None else built
    lines = []
    for service in APP_SERVICES:
        if service in pins:
            lines.append(f"pinned\t{service}\t{pins[service]}\tlinux/amd64")
        elif service in built:
            lines.append(f"built\t{service}\tparkio-{service}\t-")
    return "\n".join(lines) + "\n"


def repinned(service: str) -> str:
    return PINS[service].split("@")[0] + "@" + OTHER_DIGEST


def hosted_beta(git_sha: str, migrations: dict = None, pinned: dict = None) -> dict:
    """A hosted-beta manifest: `images` holds the built services, `pinnedImages` the digest pins."""
    recorded = manifest(git_sha, migrations, images=BUILT)
    recorded["deploymentProfile"] = "hosted-beta"
    recorded["pinnedImages"] = dict(PINS if pinned is None else pinned)
    return recorded


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

    def run_gate(self, target, deployed, image_plan=None):
        target_path = target if isinstance(target, str) else self.write("target.json", target)
        deployed_path = deployed if isinstance(deployed, str) else self.write("deployed.json", deployed)
        args = ["--target", target_path, "--deployed", deployed_path]
        if image_plan is not None:
            args += ["--image-plan", image_plan]
        return gate.main(args)

    def assert_refused(self, target, deployed, text, image_plan=None):
        with self.assertRaises(gate.Refused) as caught:
            gate.check(target, deployed, image_plan)
        self.assertIn(text, str(caught.exception))


class RealisticData(unittest.TestCase):
    def test_this_checkout_records_versioned_scripts_for_the_services_with_migrations(self):
        self.assertIn("parking-service", MIGRATIONS)
        self.assertTrue(MIGRATIONS["parking-service"])
        self.assertTrue(all(gate.SCRIPT.fullmatch(s) for scripts in MIGRATIONS.values() for s in scripts))

    def test_this_checkout_pins_services_with_migrations_by_digest(self):
        self.assertIn("auth-service", PINS)
        self.assertTrue(MIGRATIONS["auth-service"])
        self.assertTrue(BUILT)
        self.assertEqual(gate.read_plan(plan()), (BUILT, PINS))


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
    def test_malformed_migration_versions_are_refused_for_that_reason(self):
        not_object = "migrationVersions is not an object of services"
        not_scripts = "migrationVersions for parking-service is not a list of V<version>__<description>.sql names"
        repeats = "migrationVersions for parking-service repeats a script or a version"
        cases = {
            "a list instead of an object": (["V1__a.sql"], not_object),
            "an empty object": ({}, not_object),
            "a service mapped to a string": ({**MIGRATIONS, "parking-service": "V1__a.sql"}, not_scripts),
            "a non-string script": ({**MIGRATIONS, "parking-service": [1]}, not_scripts),
            "a name that is not a V-script": ({**MIGRATIONS, "parking-service": ["init.sql"]}, not_scripts),
            "a repeatable script": ({**MIGRATIONS, "parking-service": ["R__view.sql"]}, not_scripts),
            "a path": ({**MIGRATIONS, "parking-service": ["db/V1__a.sql"]}, not_scripts),
            # #290 review N1: without the check these would still be refused, but as "schema ahead".
            "a repeated script": ({**MIGRATIONS, "parking-service": ["V1__a.sql", "V1__a.sql"]}, repeats),
            "two scripts with one version": ({**MIGRATIONS, "parking-service": ["V3__a.sql", "V3__b.sql"]}, repeats),
            "one version written two ways": ({**MIGRATIONS, "parking-service": ["V1.1__a.sql", "V1_1__b.sql"]}, repeats),
        }
        for name, (value, text) in cases.items():
            with self.subTest(name):
                self.assertEqual(self.run_gate(manifest("a" * 40), manifest("b" * 40, value)), 3)
                self.assertEqual(self.run_gate(manifest("a" * 40, value), manifest("b" * 40)), 3)
                self.assert_refused(manifest("a" * 40), manifest("b" * 40, value), f"the deployed release's manifest's {text}")
                self.assert_refused(manifest("a" * 40, value), manifest("b" * 40), f"the target manifest's {text}")


class PinnedServices(Fixture):
    """hosted-beta (#290 review B1): the plan's digest pins must be the deployed release's."""

    def test_equal_pins_are_compatible_and_only_the_built_services_are_compared(self):
        message = gate.check(hosted_beta("a" * 40), hosted_beta("b" * 40), plan())
        self.assertIn(f"the {len(BUILT)} re-pointed services", message)
        self.assertIn("the digest pins equal the deployed release's", message)
        self.assertEqual(self.run_gate(hosted_beta("a" * 40), hosted_beta("b" * 40), self.write("plan", plan())), 0)

    def test_a_pinned_service_keeps_its_image_so_its_list_is_not_compared(self):
        deployed = copy.deepcopy(MIGRATIONS)
        deployed["auth-service"].append("V999__only_the_pin_has_this.sql")
        self.assertIn("compatible", gate.check(hosted_beta("a" * 40), hosted_beta("b" * 40, deployed), plan()))

    def test_a_built_service_ahead_is_still_refused_with_equal_pins(self):
        service = next(s for s in BUILT if MIGRATIONS[s])
        target = copy.deepcopy(MIGRATIONS)
        newest = latest(service)
        target[service].remove(newest)
        self.assert_refused(hosted_beta("a" * 40, target), hosted_beta("b" * 40), f"{service}: {newest}", plan())

    def test_a_pin_that_differs_from_the_deployed_release_is_refused_and_named(self):
        changed = {**PINS, "auth-service": repinned("auth-service")}
        self.assertEqual(self.run_gate(hosted_beta("a" * 40), hosted_beta("b" * 40), self.write("plan", plan(changed))), 3)
        self.assert_refused(hosted_beta("a" * 40), hosted_beta("b" * 40),
                            f"would change digest pins the deployed release runs (auth-service: {PINS['auth-service']} -> "
                            f"{repinned('auth-service')})", plan(changed))

    def test_a_record_without_pinned_images_is_refused(self):
        for value in ("absent", None, ["a"], {"auth-service": ""}):
            with self.subTest(value=value):
                deployed = hosted_beta("b" * 40)
                if value == "absent":
                    del deployed["pinnedImages"]
                else:
                    deployed["pinnedImages"] = value
                self.assert_refused(hosted_beta("a" * 40), deployed, "records no readable pinnedImages", plan())

    def test_a_pin_added_or_removed_since_the_deploy_is_refused(self):
        added = {**PINS, BUILT[0]: f"ghcr.io/example/{BUILT[0]}@{OTHER_DIGEST}"}
        self.assert_refused(hosted_beta("a" * 40), hosted_beta("b" * 40), f"{BUILT[0]}: <not pinned> ->",
                            plan(added, [s for s in BUILT if s != BUILT[0]]))
        removed = {s: i for s, i in PINS.items() if s != "auth-service"}
        self.assert_refused(hosted_beta("a" * 40), hosted_beta("b" * 40),
                            f"auth-service: {PINS['auth-service']} -> <not pinned>", plan(removed, BUILT + ["auth-service"]))

    def test_no_pins_on_either_side_are_equal(self):
        everything = [s for s in APP_SERVICES]
        self.assertIn("compatible", gate.check(manifest("a" * 40), {**manifest("b" * 40), "pinnedImages": {}},
                                               plan({}, everything)))

    def test_a_malformed_or_unreadable_plan_is_refused(self):
        cases = {
            "an unknown kind": ("shipped\tauth-service\timage\t-\n", "malformed line"),
            "a missing image": ("built\tauth-service\n", "malformed line"),
            "a service named twice": (plan() + f"built\t{BUILT[0]}\tparkio-x\t-\n", f"names {BUILT[0]} more than once"),
            "an empty plan": ("\n", "the image plan is empty"),
        }
        for name, (text, reason) in cases.items():
            with self.subTest(name):
                self.assert_refused(hosted_beta("a" * 40), hosted_beta("b" * 40), reason, text)
        missing = str(Path(self.tmp.name) / "no-plan")
        self.assertEqual(self.run_gate(hosted_beta("a" * 40), hosted_beta("b" * 40), missing), 3)


if __name__ == "__main__":
    unittest.main(verbosity=1)
