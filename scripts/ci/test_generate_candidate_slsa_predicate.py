#!/usr/bin/env python3
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
_MOD = ROOT / "scripts" / "ci" / "generate-candidate-slsa-predicate.py"
_spec = importlib.util.spec_from_file_location("gen_pred", _MOD)
_mod = importlib.util.module_from_spec(_spec)
assert _spec.loader is not None
_spec.loader.exec_module(_mod)
build_predicate = _mod.build_predicate


class PredicateContract(unittest.TestCase):
    def test_separates_images_run_from_publish_run(self):
        pred = build_predicate(
            source_sha="a" * 40,
            digest="sha256:" + "b" * 64,
            image_name="ghcr.io/adberilgen35/parkio/user-service",
            candidate_images_run_id="111",
            publish_run_id="222",
            workflow_ref="ADBERILGEN35/parkio/.github/workflows/candidate-publish.yml@refs/heads/api",
        )
        ext = pred["buildDefinition"]["externalParameters"]
        self.assertEqual(ext["candidateImagesRunId"], "111")
        self.assertEqual(ext["candidatePublishRunId"], "222")
        self.assertEqual(pred["runDetails"]["metadata"]["invocationId"], "222")
        self.assertNotEqual(ext["candidateImagesRunId"], pred["runDetails"]["metadata"]["invocationId"])
        self.assertEqual(ext["publishedDigest"], "sha256:" + "b" * 64)
        self.assertIn("a" * 40, ext["source"])
        meta = pred["runDetails"]["metadata"]
        self.assertNotIn("startedOn", meta)
        self.assertNotIn("finishedOn", meta)

    def test_cli_writes_file(self):
        import subprocess

        with tempfile.TemporaryDirectory() as td:
            out = Path(td) / "p.json"
            subprocess.run(
                [
                    sys.executable,
                    str(ROOT / "scripts/ci/generate-candidate-slsa-predicate.py"),
                    "--source-sha",
                    "c" * 40,
                    "--digest",
                    "d" * 64,
                    "--image-name",
                    "ghcr.io/adberilgen35/parkio/auth-service",
                    "--candidate-images-run-id",
                    "9",
                    "--publish-run-id",
                    "10",
                    "--workflow-ref",
                    "ADBERILGEN35/parkio/.github/workflows/candidate-publish.yml@refs/heads/api",
                    "--out",
                    str(out),
                ],
                check=True,
            )
            data = json.loads(out.read_text(encoding="utf-8"))
            self.assertEqual(data["buildDefinition"]["externalParameters"]["candidateImagesRunId"], "9")
            self.assertEqual(data["runDetails"]["metadata"]["invocationId"], "10")


if __name__ == "__main__":
    unittest.main()
