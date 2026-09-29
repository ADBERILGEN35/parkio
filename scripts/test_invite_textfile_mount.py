#!/usr/bin/env python3
"""Mutation tests: the approved mount is one identity, not a path wildcard."""
import copy
import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("mount_guard", ROOT / "scripts/lib/assert-invite-textfile-mount.py")
GUARD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GUARD)


class MountGuardTest(unittest.TestCase):
    def overlay(self, public=False):
        suffix = "public" if public else "dark"
        return GUARD.yaml.load((ROOT / f"docker/docker-compose.invite-{suffix}.yml").read_text(),
                               Loader=GUARD.ComposeLoader)

    def model(self):
        mounts = [{"type": "bind", "source": source, "target": target, "read_only": True}
                  for source, target in (("/proc", "/host/proc"), ("/sys", "/host/sys"),
                                         ("/", "/host/root"), (GUARD.HOST, GUARD.TARGET))]
        return {"services": {"node-exporter": {"volumes": mounts,
                "command": ["--collector.textfile.directory=/textfile-collector"]},
                "prometheus": {}}}

    def test_exact_current_overlays_and_model(self):
        for public in (False, True):
            GUARD.validate_overlay(self.overlay(public), public)
        GUARD.validate_model(self.model())

    def test_reject_wrong_broad_release_source_target_and_read_write(self):
        for source, target, mode in (
            ("/wrong", GUARD.TARGET, "ro"),
            ("/var/lib/parkio", GUARD.TARGET, "ro"),
            ("/opt/parkio/releases/" + "a" * 40 + "/textfile", GUARD.TARGET, "ro"),
            (GUARD.HOST, "/wrong", "ro"),
            (GUARD.HOST, GUARD.TARGET, "rw"),
        ):
            with self.subTest(source=source, target=target, mode=mode):
                overlay = self.overlay()
                overlay["services"]["node-exporter"]["volumes"] = [f"{source}:{target}:{mode}"]
                with self.assertRaises(ValueError):
                    GUARD.validate_overlay(overlay)
                model = self.model()
                model["services"]["node-exporter"]["volumes"][-1].update(
                    source=source, target=target, read_only=mode == "ro")
                with self.assertRaises(ValueError):
                    GUARD.validate_model(model)

    def test_reject_wrong_service_and_second_mount(self):
        for move in (False, True):
            overlay = self.overlay()
            overlay["services"]["tempo"]["volumes"] = [GUARD.MOUNT]
            model = self.model()
            model["services"]["prometheus"]["volumes"] = [copy.deepcopy(
                model["services"]["node-exporter"]["volumes"][-1])]
            if move:
                overlay["services"]["node-exporter"]["volumes"] = []
                model["services"]["node-exporter"]["volumes"].pop()
            with self.assertRaises(ValueError):
                GUARD.validate_overlay(overlay)
            with self.assertRaises(ValueError):
                GUARD.validate_model(model)
        overlay = self.overlay()
        overlay["services"]["node-exporter"]["volumes"].append("/extra:/extra:ro")
        model = self.model()
        model["services"]["node-exporter"]["volumes"].append(
            {"type": "bind", "source": "/extra", "target": "/extra", "read_only": True})
        with self.assertRaises(ValueError):
            GUARD.validate_overlay(overlay)
        with self.assertRaises(ValueError):
            GUARD.validate_model(model)

    def test_reject_extra_service_or_exporter_settings_and_wrong_collector(self):
        overlay = self.overlay()
        overlay["services"]["unexpected"] = {}
        with self.assertRaises(ValueError):
            GUARD.validate_overlay(overlay)
        overlay = self.overlay()
        overlay["services"]["node-exporter"]["privileged"] = True
        with self.assertRaises(ValueError):
            GUARD.validate_overlay(overlay)
        model = self.model()
        model["services"]["node-exporter"]["command"] = ["--collector.textfile.directory=/wrong"]
        with self.assertRaises(ValueError):
            GUARD.validate_model(model)


if __name__ == "__main__":
    unittest.main()
