"""Offline tests for UI7 Editorial build-time asset gate; stdlib only."""
import base64
import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import validate_ui7_editorial_assets as gate

# Real WebP payload generated once for tests; Pillow is NOT required to run.
SAMPLE_WEBP_B64 = (
    'UklGRlYEAABXRUJQVlA4IEoEAAAwfwCdASoABEACPqVSpk8mJKOiIAgAwBSJaW7hd2EbQAnsA99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe'
    '+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32'
    'ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2T'
    'kPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych'
    '77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPf'
    'bJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77Z'
    'OQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJy'
    'Hvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ9'
    '9snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvt'
    'k5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99sn'
    'Ie+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D'
    '32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+'
    '2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvrAAA/v/AadiO39W0wdPAAAAAAAAAAAAAAAAAAA'
    'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA='
)
SAMPLE = base64.b64decode(SAMPLE_WEBP_B64)
HEADER = gate.MANIFEST_HEADER + "\n"
SOURCE = "1zTH98iNz4rsDIpJ2LUINsPgQO6TAqP5i"


class EditorialPublicationGateTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.directory = Path(self.tmp.name) / "production"
        self.directory.mkdir()
        self.manifest = self.directory / "manifest.tsv"
        self.asset_name = "genre_new_wave_cover.webp"
        self.asset = self.directory / self.asset_name
        self.asset.write_bytes(SAMPLE)
        self.manifest.write_text(HEADER + self.row(), encoding="utf-8")
        self.patch_assets = patch.object(gate, "ASSETS", self.directory)
        self.patch_manifest = patch.object(gate, "MANIFEST", self.manifest)
        self.patch_assets.start(); self.patch_manifest.start()
        self.addCleanup(self.patch_assets.stop); self.addCleanup(self.patch_manifest.stop)

    def row(self, variant="cover", sha=None, w="1024", h="576", source=SOURCE, approval="APPROVED"):
        return "\t".join([
            "new_wave", variant, f"genre_new_wave_{variant}.webp",
            sha or hashlib.sha256(SAMPLE).hexdigest(), w, h,
            source, approval, "YES", "YES", "YES"
        ]) + "\n"

    def test_true_webp_and_valid_manifest(self):
        self.assertEqual((1024, 576), gate.webp_size(SAMPLE))
        self.assertEqual([], gate.validate())

    def test_missing_header_or_version_fails(self):
        self.manifest.write_text(self.row())
        self.assertTrue(gate.validate())
        self.manifest.write_text(HEADER.replace("V1", "V2") + self.row())
        self.assertTrue(gate.validate())

    def test_duplicate_or_unapproved_rows_fail(self):
        self.manifest.write_text(HEADER + self.row() + self.row())
        self.assertTrue(any("duplicate" in x for x in gate.validate()))
        self.manifest.write_text(HEADER + self.row(approval="DRAFT"))
        self.assertTrue(gate.validate())

    def test_hash_mismatch_and_stray_webp_fail(self):
        self.manifest.write_text(HEADER + self.row(sha="f" * 64))
        self.assertTrue(any("SHA" in x for x in gate.validate()))
        self.manifest.write_text(HEADER + self.row())
        (self.directory / "genre_rock_cover.webp").write_bytes(SAMPLE)
        self.assertTrue(any("undeclared" in x for x in gate.validate()))

    def test_wrong_variant_geometry_fails(self):
        self.asset.rename(self.directory / "genre_new_wave_portrait_left.webp")
        self.manifest.write_text(HEADER + self.row(variant="portrait_left"))
        self.assertTrue(any("geometry" in x for x in gate.validate()))
        self.assertTrue(gate.geometry_valid("portrait_left", 768, 1024))
        self.assertFalse(gate.geometry_valid("cover", 4096, 4096))

    def test_invalid_drive_provenance_fails(self):
        self.manifest.write_text(HEADER + self.row(source="é" * 16))
        self.assertTrue(any("provenance" in x for x in gate.validate()))

    def test_riot_riff_size_mismatch_and_truncated_chunks(self):
        self.assertRaises(ValueError, gate.webp_size, SAMPLE[:-1])
        self.assertRaises(ValueError, gate.webp_size, SAMPLE + b"INVALID")
        bad = bytearray(SAMPLE)
        bad[16:20] = (len(bad) * 4).to_bytes(4, "little")
        self.assertRaises(ValueError, gate.webp_size, bytes(bad))

    def test_animated_vp8x_is_rejected(self):
        vp8x_body = bytes([0x02, 0, 0, 0]) + (1023).to_bytes(3, "little") + (575).to_bytes(3, "little")
        vp8x_chunk = b"VP8X" + (10).to_bytes(4, "little") + vp8x_body
        body = b"WEBP" + vp8x_chunk + SAMPLE[12:]
        animated = b"RIFF" + len(body).to_bytes(4, "little") + body
        self.assertRaises(ValueError, gate.webp_size, animated)


if __name__ == "__main__":
    unittest.main()
