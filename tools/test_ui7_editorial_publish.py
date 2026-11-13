"""Regression checks for the no-overwrite, dry-run-first UI7 Editorial publisher."""
import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import validate_ui7_editorial_assets as gate
import ui7_editorial_publish as pub
from test_validate_ui7_editorial_assets import SAMPLE, SOURCE


class EditorialPublishingTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.assets = root / "production"
        self.assets.mkdir()
        self.manifest = self.assets / "manifest.tsv"
        self.manifest.write_text(gate.MANIFEST_HEADER + "\n")
        self.original = root / "approved_original.webp"
        self.original.write_bytes(SAMPLE)
        self.args = (self.original, "new_wave", "cover", SOURCE)

    def test_preview_is_non_mutating(self):
        row = pub.publish(*self.args, assets=self.assets)
        self.assertTrue(row.startswith("new_wave\tcover\tgenre_new_wave_cover.webp"))
        self.assertEqual(["manifest.tsv"], sorted(p.name for p in self.assets.iterdir()))

    def test_no_install_without_explicit_art_approval(self):
        with self.assertRaises(ValueError):
            pub.publish(*self.args, assets=self.assets, install=True)
        self.assertEqual(["manifest.tsv"], sorted(p.name for p in self.assets.iterdir()))

    def test_real_one_file_install_and_manifest_hash_consistent(self):
        pub.publish(*self.args, assets=self.assets, install=True, approval_confirmed=True)
        target = self.assets / "genre_new_wave_cover.webp"
        self.assertEqual(SAMPLE, target.read_bytes())
        self.assertIn(hashlib.sha256(SAMPLE).hexdigest(), self.manifest.read_text())
        with patch.object(gate, "ASSETS", self.assets), patch.object(gate, "MANIFEST", self.manifest):
            self.assertEqual([], gate.validate())
        self.assertEqual(SAMPLE, self.original.read_bytes())  # source is immutable

    def test_duplicate_install_never_overwrites(self):
        pub.publish(*self.args, assets=self.assets, install=True, approval_confirmed=True)
        with self.assertRaises(FileExistsError):
            pub.publish(*self.args, assets=self.assets, install=True, approval_confirmed=True)
        self.assertEqual(1, self.manifest.read_text().count("genre_new_wave_cover.webp"))

    def test_bad_geometry_and_unknown_slug_refused_before_any_writes(self):
        with self.assertRaises(ValueError):
            pub.publish(self.original, "new_wave", "portrait_left", SOURCE, assets=self.assets, install=True, approval_confirmed=True)
        with self.assertRaises(ValueError):
            pub.publish(self.original, "invalid_genre", "cover", SOURCE, assets=self.assets, install=True, approval_confirmed=True)
        self.assertEqual(["manifest.tsv"], sorted(p.name for p in self.assets.iterdir()))

    def test_bad_existing_manifest_refuses_install(self):
        self.manifest.write_text("# WRONG VERSION\n")
        with self.assertRaises(ValueError):
            pub.publish(*self.args, assets=self.assets, install=True, approval_confirmed=True)
        self.assertFalse((self.assets / "genre_new_wave_cover.webp").exists())

    def test_publication_rolls_back_when_final_gate_rejects(self):
        original = self.manifest.read_bytes()
        call_count = 0
        actual = gate.validate

        def reject_final():
            nonlocal call_count
            call_count += 1
            if call_count == 2:
                return ["simulated failure after install"]
            return actual()

        with patch.object(gate, "validate", side_effect=reject_final):
            with self.assertRaises(ValueError):
                pub.publish(*self.args, assets=self.assets, install=True, approval_confirmed=True)
        self.assertEqual(original, self.manifest.read_bytes())
        self.assertFalse((self.assets / "genre_new_wave_cover.webp").exists())

    def test_symlink_source_refused(self):
        alias = Path(self.temp.name) / "link.webp"
        alias.symlink_to(self.original)
        with self.assertRaises(ValueError):
            pub.publish(alias, "new_wave", "cover", SOURCE, assets=self.assets)


if __name__ == "__main__":
    unittest.main()
