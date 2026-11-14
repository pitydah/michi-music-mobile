"""Offline tests for the UI7 Mosaico destacado inventory gate; stdlib only."""
from __future__ import annotations

import hashlib
from pathlib import Path
import tempfile
import unittest

import validate_ui7_mosaic_assets as gate
from test_validate_ui7_editorial_assets import SAMPLE

HEADER = gate.HEADER + "\n"


class MosaicGenreKeyTests(unittest.TestCase):
    def test_camel_case_and_siglas_resolve_to_canonical_keys(self):
        expected = {
            "UI7_MOSAICO_001_Rock.webp": "rock",
            "UI7_MOSAICO_002_Jazz.webp": "jazz",
            "UI7_MOSAICO_003_SynthPop.webp": "synth-pop",
            "UI7_MOSAICO_004_Clasica.webp": "clasica",
            "UI7_MOSAICO_009_HipHop.webp": "hip-hop",
            "UI7_MOSAICO_019_RnB.webp": "r&b",
            "UI7_MOSAICO_028_BossaNova.webp": "bossa nova",
            "UI7_MOSAICO_029_LoFi.webp": "lo-fi",
            "UI7_MOSAICO_034_DrumBass.webp": "drum bass",
            "UI7_MOSAICO_043_KPop.webp": "k-pop",
            "UI7_MOSAICO_044_JPop.webp": "j-pop",
            "UI7_MOSAICO_058_EDM.webp": "edm",
            "UI7_MOSAICO_081_RockRoll.webp": "rock roll",
            "UI7_MOSAICO_131_UKGarage.webp": "uk garage",
            "UI7_MOSAICO_160_YeYe.webp": "ye ye",
            "UI7_MOSAICO_166_HiNRG.webp": "hi nrg",
            "UI7_MOSAICO_170_GFunk.webp": "g funk",
            "UI7_MOSAICO_184_MPB.webp": "mpb",
            "UI7_MOSAICO_188_LiquidDrumBass.webp": "liquid drum bass",
            "UI7_MOSAICO_189_IDM.webp": "idm",
            "UI7_MOSAICO_214_Neoclasica.webp": "neoclasica",
            "UI7_MOSAICO_215_MusicaDeCamara.webp": "musica de camara",
            "UI7_MOSAICO_216_JRock.webp": "j rock",
            "UI7_MOSAICO_217_ArtRock.webp": "art rock",
            "UI7_MOSAICO_218_NDW.webp": "ndw",
            "UI7_MOSAICO_225_Rai.webp": "rai",
            "UI7_MOSAICO_233_RussianRock.webp": "russian rock",
            "UI7_MOSAICO_241_Qawwali.webp": "qawwali",
            "UI7_MOSAICO_249_Klezmer.webp": "klezmer",
            "UI7_MOSAICO_262_Gqom.webp": "gqom",
            "UI7_MOSAICO_275_VisualKei.webp": "visual kei",
            "UI7_MOSAICO_281_Chiptune.webp": "chiptune",
            "UI7_MOSAICO_290_Chacarera.webp": "chacarera",
            "UI7_MOSAICO_297_ModalJazz.webp": "modal jazz",
            "UI7_MOSAICO_305_GuajiraCubana.webp": "guajira cubana",
            "UI7_MOSAICO_311_ChaChaCha.webp": "cha cha cha",
            "UI7_MOSAICO_312_Timba.webp": "timba",
        }
        for filename, key in expected.items():
            self.assertEqual(key, gate.genre_key(filename), filename)

    def test_unknown_names_never_invent_a_key(self):
        for filename in ("UI7_MOSAICO_1_Rock.webp", "UI7_MOSAICO_001_.webp",
                         "UI7_MOSAICO_001_Rock.png", "Rock.webp",
                         "UI7_MOSAICO_001_Rock copy.webp", "UI7_MOSAICO_001_Rock/Roll.webp"):
            self.assertIsNone(gate.genre_key(filename), filename)

    def test_lookup_index_separates_closely_related_genres(self):
        self.assertEqual("hip hop", gate.index_key("Hip-Hop"))
        self.assertEqual(gate.index_key("hip-hop"), gate.index_key("hip hop"))
        self.assertEqual("j rock", gate.index_key("J-Rock"))
        self.assertNotEqual(gate.index_key("rock"), gate.index_key("rock roll"))


class MosaicInventoryGateTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.folder = Path(self.tmp.name)
        self.rows = [self.row(number) for number in range(1, gate.EXPECTED_ASSETS + 1)]
        for cells in self.rows:
            (self.folder / cells[1]).write_bytes(SAMPLE)
        self.manifest = self.folder / "manifest.tsv"
        self.write()

    def row(self, number: int, key: str | None = None, filename: str | None = None,
            sha: str | None = None, width: int = 1024, height: int = 576,
            approval: str = gate.APPROVAL) -> list[str]:
        name = filename or f"UI7_MOSAICO_{number:03d}_Genre{number}.webp"
        return [key or gate.genre_key(name), name,
                sha or hashlib.sha256(SAMPLE).hexdigest(),
                str(width), str(height), approval]

    def write(self, rows=None):
        rows = self.rows if rows is None else rows
        self.manifest.write_text(HEADER + "".join("\t".join(r) + "\n" for r in rows),
                                 encoding="utf-8", newline="\n")

    def test_accepts_unchanged_complete_pack(self):
        self.assertEqual([], gate.validate(self.folder))

    def test_rewrite_is_deterministic(self):
        gate.write_manifest(self.folder)
        first = self.manifest.read_bytes()
        gate.write_manifest(self.folder)
        self.assertEqual(first, self.manifest.read_bytes())
        self.assertTrue(first.startswith(gate.HEADER.encode()))
        self.assertEqual([], gate.validate(self.folder))

    def test_rejects_mismatched_file_hash(self):
        rows = [self.row(1, sha="a" * 64)] + self.rows[1:]
        self.write(rows)
        self.assertTrue(any("SHA" in issue for issue in gate.validate(self.folder)))

    def test_rejects_duplicate_or_ambiguous_keys(self):
        rows = [self.row(1)] + [self.row(2, filename="UI7_MOSAICO_002_Genre1.webp",
                                            key="genre1")] + self.rows[2:]
        (self.folder / "UI7_MOSAICO_002_Genre1.webp").write_bytes(SAMPLE)
        self.write(rows)
        self.assertTrue(any("duplicate genre_key" in issue for issue in gate.validate(self.folder)))

    def test_rejects_key_that_filename_does_not_spell(self):
        rows = [self.row(1, key="jazz")] + self.rows[1:]
        self.write(rows)
        self.assertTrue(any("does not match filename" in issue for issue in gate.validate(self.folder)))

    def test_rejects_undeclared_and_missing_files(self):
        (self.folder / "UI7_MOSAICO_999_Extra.webp").write_bytes(SAMPLE)
        self.assertTrue(any("undocumented" in issue for issue in gate.validate(self.folder)))
        (self.folder / "UI7_MOSAICO_999_Extra.webp").unlink()
        (self.folder / self.rows[5][1]).unlink()
        self.assertTrue(any("missing/unsafe" in issue for issue in gate.validate(self.folder)))

    def test_rejects_wrong_approval_and_count(self):
        rows = list(self.rows)
        rows[0] = self.row(1, approval="DRAFT")
        self.write(rows)
        self.assertTrue(any("user-confirmed" in issue for issue in gate.validate(self.folder)))
        self.write(self.rows[:-1])
        self.assertTrue(any("expected exactly" in issue for issue in gate.validate(self.folder)))

    def test_rejects_numbering_gaps(self):
        rows = list(self.rows)
        rows[3] = self.row(4, filename="UI7_MOSAICO_009_GapTest.webp", key="gap test")
        (self.folder / "UI7_MOSAICO_009_GapTest.webp").write_bytes(SAMPLE)
        self.write(rows)
        self.assertTrue(any("gap-free" in issue for issue in gate.validate(self.folder)))

    def test_rejects_declared_size_that_differs_from_webp(self):
        rows = [self.row(1, width=1200, height=400)] + self.rows[1:]
        self.write(rows)
        self.assertTrue(any("declared size" in issue for issue in gate.validate(self.folder)))

    def test_rejects_malformed_manifest_header(self):
        self.manifest.write_text("# UI7_MOSAICO_MANIFEST_V2\n", encoding="utf-8")
        self.assertTrue(any("header" in issue for issue in gate.validate(self.folder)))


class ShippedMosaicPackTests(unittest.TestCase):
    def test_shipped_pack_declares_the_approved_312_rows(self):
        lines = [line for line in gate.MANIFEST.read_text(encoding="utf-8").splitlines()
                 if line and not line.startswith("#")]
        self.assertEqual(312, len(lines))
        self.assertEqual(gate.EXPECTED_ASSETS, len(lines))
        numbers = [int(line.split("\t")[1][12:15]) for line in lines]
        self.assertEqual(list(range(1, 313)), numbers)


if __name__ == "__main__":
    unittest.main()
