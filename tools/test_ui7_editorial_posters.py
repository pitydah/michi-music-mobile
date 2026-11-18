"""Focused tests for UI7 complete horizontal posters (source WebP remains untouched)."""
from __future__ import annotations
import hashlib
from pathlib import Path
import tempfile
import unittest

import validate_ui7_editorial_posters as gate
import ui7_editorial_poster_pack as pack
from test_validate_ui7_editorial_assets import SAMPLE

SHEET = "UI7_EDITORIAL_LAMINA_03_NewWave_ItaloDisco_RnB_Alternativo.png"


class CompletePosterContractTests(unittest.TestCase):
    """The pack is the whole inventory, not whatever happens to be packaged."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.folder = Path(self.tmp.name)
        self.slugs = sorted(gate.gate.source_keys())
        for slug in self.slugs:
            (self.folder / f"genre_{slug}_editorial.webp").write_bytes(SAMPLE)
        self.manifest = self.folder / "manifest.tsv"
        self.write()

    def row(self, slug, sha=None, state="USER_CONFIRMED_COMPLETE",
            presentation="POSTER_COMPLETE", ratio=(1024, 576)):
        return "\t".join((slug, f"genre_{slug}_editorial.webp",
                          sha or hashlib.sha256(SAMPLE).hexdigest(),
                          str(ratio[0]), str(ratio[1]), SHEET,
                          state, presentation)) + "\n"

    def write(self, **kwargs):
        self.manifest.write_text(
            gate.HEADER + "\n" + "".join(self.row(slug, **kwargs) for slug in self.slugs),
            encoding="utf-8")

    def test_accepts_a_complete_horizontal_pack(self):
        self.assertEqual([], gate.validate(self.folder))

    def test_rejects_mismatched_file_hash(self):
        self.manifest.write_text(
            gate.HEADER + "\n" + "".join(
                self.row(slug, sha="a" * 64 if slug == "new_wave" else None)
                for slug in self.slugs), encoding="utf-8")
        self.assertTrue(any("SHA" in issue for issue in gate.validate(self.folder)))

    def test_rejects_malformed_dimensions(self):
        self.manifest.write_text(
            gate.HEADER + "\n" + "".join(
                self.row(slug, ratio=(900, 600) if slug == "new_wave" else (1024, 576))
                for slug in self.slugs), encoding="utf-8")
        self.assertTrue(any("dimensions" in issue for issue in gate.validate(self.folder)))

    def test_rejects_unlisted_posters(self):
        (self.folder / "extra.webp").write_bytes(SAMPLE)
        self.assertTrue(any("undocumented" in issue for issue in gate.validate(self.folder)))

    def test_rejects_doubled_slug_rows(self):
        self.manifest.write_text(
            gate.HEADER + "\n" + "".join(self.row(slug) for slug in self.slugs)
            + self.row("new_wave"), encoding="utf-8")
        self.assertTrue(any("duplicate" in issue for issue in gate.validate(self.folder)))

    def test_refuses_unapproved_state_and_wrong_presentation(self):
        self.write(state="DRAFT")
        self.assertTrue(gate.validate(self.folder))
        self.write(presentation="CROP_TO_PORTRAIT")
        self.assertTrue(gate.validate(self.folder))

    def test_rejected_state_is_never_publishable(self):
        self.write(state="REJECTED")
        self.assertTrue(any("rejected" in issue for issue in gate.validate(self.folder)))

    def test_pending_pack_loads_but_is_not_certified(self):
        self.write(state="IMPORTED_PENDING_VISUAL_APPROVAL",
                   presentation="POSTER_IMPORTED_PENDING")
        self.assertEqual([], gate.validate(self.folder))
        certified = gate.validate(self.folder, certified=True)
        self.assertTrue(any("pending visual approval" in issue for issue in certified))

    def test_a_fully_approved_pack_still_certifies(self):
        # The certified gate must reject pending artwork, never approved artwork.
        self.assertEqual([], gate.validate(self.folder, certified=True))

    def test_a_missing_poster_is_not_a_smaller_pack(self):
        (self.folder / "genre_new_wave_editorial.webp").unlink()
        self.manifest.write_text(
            gate.HEADER + "\n" + "".join(
                self.row(slug) for slug in self.slugs if slug != "new_wave"),
            encoding="utf-8")
        problems = gate.validate(self.folder)
        self.assertTrue(any("approved slug without poster" in issue for issue in problems))
        self.assertTrue(any("found 664" in issue for issue in problems))

    def test_empty_manifest_is_never_a_valid_pack(self):
        for path in self.folder.glob("*.webp"):
            path.unlink()
        self.manifest.write_text(gate.HEADER + "\n")
        problems = gate.validate(self.folder)
        self.assertTrue(any("found 0" in issue for issue in problems))

    def test_pack_rejects_existing_output_before_writing(self):
        source = self.folder / "source.zip"
        source.write_bytes(b"junk")
        output = self.folder / "out.zip"
        output.write_bytes(b"untouched")
        with self.assertRaises(FileExistsError):
            pack.package(source, output)
        self.assertEqual(b"untouched", output.read_bytes())


class ShippedPosterPackTests(unittest.TestCase):
    def test_shipped_pack_declares_the_full_inventory(self):
        lines = [line for line in gate.POSTERS.joinpath("manifest.tsv")
                 .read_text(encoding="utf-8").splitlines() if line and not line.startswith("#")]
        self.assertEqual(gate.EXPECTED_POSTERS, len(lines))
        rows = [line.split("\t") for line in lines]
        self.assertEqual(gate.EXPECTED_POSTERS, len({row[0] for row in rows}))
        self.assertEqual(gate.EXPECTED_POSTERS, len({row[1] for row in rows}))
        self.assertEqual(gate.gate.source_keys(), {row[0] for row in rows})
        approved = [row for row in rows if row[6] == gate.APPROVAL]
        pending = [row for row in rows if row[6] == gate.PENDING_APPROVAL]
        self.assertEqual(312, len(approved))
        self.assertEqual(353, len(pending))
        for row in approved:
            self.assertEqual(gate.PRESENTATION, row[7])
        for row in pending:
            self.assertEqual(gate.PENDING_PRESENTATION, row[7])
        for row in rows:
            self.assertTrue(row[5].startswith("UI7_EDITORIAL_LAMINA_"))
            self.assertGreaterEqual(int(row[4]), 200)


if __name__ == "__main__":
    unittest.main()
