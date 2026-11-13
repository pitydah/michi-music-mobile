"""Focused tests for UI7 complete horizontal posters (source WebP remains untouched)."""
from __future__ import annotations
import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import validate_ui7_editorial_posters as gate
import ui7_editorial_poster_pack as pack
from test_validate_ui7_editorial_assets import SAMPLE


class CompletePosterContractTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.folder = Path(self.tmp.name)
        self.name = "genre_new_wave_editorial.webp"
        self.src = self.folder / self.name
        self.src.write_bytes(SAMPLE)
        self.manifest = self.folder / "manifest.tsv"
        self.manifest.write_text(gate.HEADER + "\n" + self.row(), encoding="utf-8")

    def row(self, sha=None, state="USER_CONFIRMED_COMPLETE", ratio=(1024, 576)):
        return "\t".join(("new_wave", self.name, sha or hashlib.sha256(SAMPLE).hexdigest(),
                          str(ratio[0]), str(ratio[1]),
                          "UI7_EDITORIAL_LAMINA_03_NewWave_ItaloDisco_RnB_Alternativo.png",
                          state, "POSTER_COMPLETE")) + "\n"

    def test_accepts_unchanged_horizontal_complete_webp(self):
        self.assertEqual([], gate.validate(self.folder))

    def test_rejects_mismatched_file_hash(self):
        self.manifest.write_text(gate.HEADER + "\n" + self.row(sha="a" * 64))
        self.assertTrue(any("SHA" in issue for issue in gate.validate(self.folder)))

    def test_rejects_malformed_dimensions(self):
        self.manifest.write_text(gate.HEADER + "\n" + self.row(ratio=(900, 600)))
        self.assertTrue(any("dimensions" in issue for issue in gate.validate(self.folder)))

    def test_rejects_unlisted_posters(self):
        (self.folder / "extra.webp").write_bytes(SAMPLE)
        self.assertTrue(any("undocumented" in issue for issue in gate.validate(self.folder)))

    def test_rejects_doubled_slug_rows(self):
        self.manifest.write_text(gate.HEADER + "\n" + self.row() + self.row())
        self.assertTrue(any("duplicate" in issue for issue in gate.validate(self.folder)))

    def test_refuses_unapproved_state_and_wrong_presentation(self):
        self.manifest.write_text(gate.HEADER + "\n" + self.row(state="DRAFT"))
        self.assertTrue(gate.validate(self.folder))
        self.manifest.write_text((gate.HEADER + "\n" + self.row()).replace("POSTER_COMPLETE", "CROP_TO_PORTRAIT"))
        self.assertTrue(gate.validate(self.folder))

    def test_empty_manifest_is_legal_fallback(self):
        self.src.unlink()
        self.manifest.write_text(gate.HEADER + "\n")
        self.assertEqual([], gate.validate(self.folder))

    def test_pack_rejects_existing_output_before_writing(self):
        source = self.folder / "source.zip"
        source.write_bytes(b"junk")
        output = self.folder / "out.zip"
        output.write_bytes(b"untouched")
        with self.assertRaises(FileExistsError):
            pack.package(source, output)
        self.assertEqual(b"untouched", output.read_bytes())


if __name__ == "__main__":
    unittest.main()
