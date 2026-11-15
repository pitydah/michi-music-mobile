#!/usr/bin/env python3
"""K29 source-isolation regressions executing the candidate @Query SQL in SQLite.

No Android SDK, network, MediaStore access or music files required. This is a
behavioral SQL gate, NOT a substitute for Room/KSP or on-device WorkManager.
"""
from __future__ import annotations
from pathlib import Path
import re
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[1]
DAO = ROOT / "data/src/main/java/org/michimusic/data/artist/ArtistArtworkCatalogJournal.kt"


def sql_for(name: str) -> str:
    source = DAO.read_text(encoding="utf-8")
    pattern = re.compile(
        r'@Query\((?:"""(?P<block>.*?)"""|"(?P<inline>[^"\n]*)")\)\s*'
        r'suspend fun (?P<name>\w+)\(', re.S,
    )
    for hit in pattern.finditer(source):
        if hit.group("name") == name:
            return (hit.group("block") or hit.group("inline")).strip()
    raise AssertionError(f"No actual DAO @Query found for {name}")


class RootFenceSQLTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.addCleanup(self.db.close)
        self.db.execute("""CREATE TABLE artist_artwork_auto_outbox (
            libraryId TEXT NOT NULL, creditKey TEXT NOT NULL,
            associationId TEXT NOT NULL, albumKey TEXT NOT NULL,
            sourceId TEXT NOT NULL, state TEXT NOT NULL,
            updatedAtMs INTEGER NOT NULL,
            PRIMARY KEY(libraryId, creditKey, associationId, albumKey, sourceId))""")

        self.db.execute("""CREATE TABLE artist_album_credit_binding (
            libraryId TEXT NOT NULL, albumKey TEXT NOT NULL,
            creditKey TEXT NOT NULL, associationId TEXT NOT NULL,
            confirmedAtMs INTEGER NOT NULL,
            PRIMARY KEY(libraryId, albumKey, creditKey))""")
        self.db.execute("""CREATE TABLE artist_artwork_source_binding_proof (
            libraryId TEXT NOT NULL, albumKey TEXT NOT NULL,
            creditKey TEXT NOT NULL, associationId TEXT NOT NULL,
            sourceId TEXT NOT NULL, confirmedAtMs INTEGER NOT NULL)""")

    def bind(self, album, credit="artist", association="assoc"):
        self.db.execute("INSERT INTO artist_album_credit_binding VALUES ('lib', ?, ?, ?, 1)",
                        (album, credit, association))
        self.db.execute("""INSERT INTO artist_artwork_source_binding_proof
             SELECT DISTINCT libraryId,albumKey,creditKey,associationId,sourceId,1
             FROM artist_artwork_auto_outbox
             WHERE libraryId='lib' AND albumKey=? AND creditKey=? AND associationId=?""",
             (album,credit,association))

    def seed(self, album, root, state="PENDING", at=1, credit="artist", association="assoc"):
        self.db.execute("""INSERT INTO artist_artwork_auto_outbox VALUES
            ('lib', ?, ?, ?, ?, ?, ?)""", (credit, association, album, root, state, at))

    def query(self, name, **args):
        return self.db.execute(sql_for(name), args)

    def row(self, album):
        return self.db.execute("SELECT sourceId, state, updatedAtMs FROM artist_artwork_auto_outbox WHERE albumKey=?", (album,)).fetchone()

    def test_old_root_cannot_defer_new_root(self):
        self.seed("one", "saf-new")
        changed = self.query("deferPendingForAssociation", libraryId="lib", creditKey="artist",
                             associationId="assoc", sourceId="device-old", notBeforeMs=200).rowcount
        self.assertEqual(changed, 0)
        self.assertEqual(self.row("one"), ("saf-new", "PENDING", 1))
        self.assertEqual(self.query("deferPendingForAssociation", libraryId="lib", creditKey="artist",
                         associationId="assoc", sourceId="saf-new", notBeforeMs=200).rowcount, 1)
        self.assertEqual(self.row("one"), ("saf-new", "PENDING", 200))

    def test_old_root_cannot_finish_new_root_event_or_missing_association(self):
        self.seed("two", "saf-new")
        shared = dict(libraryId="lib", creditKey="artist", associationId="assoc", sourceId="device-old", nowMs=99)
        self.assertEqual(self.query("finish", albumKey="two", state="STALE_CONTEXT", **shared).rowcount, 0)
        self.assertEqual(self.query("finishMissingAssociation", **shared).rowcount, 0)
        self.assertEqual(self.row("two"), ("saf-new", "PENDING", 1))
        self.assertEqual(self.query("finish", albumKey="two", state="READY", libraryId="lib",
                         creditKey="artist", associationId="assoc", sourceId="saf-new", nowMs=99).rowcount, 1)
        self.assertEqual(self.row("two"), ("saf-new", "READY", 99))

    def test_old_root_fanout_over_1024_cannot_hide_new_root(self):
        for i in range(1030):
            self.seed(f"old-{i}", "device-old")
        self.seed("new", "saf-new", at=5)
        self.bind("new")
        base = dict(libraryId="lib", creditKey="artist", associationId="assoc", nowMs=10)
        older = self.query("pending", **base).fetchall()
        current = self.query("pendingForRoot", sourceId="saf-new", **base).fetchall()
        self.assertEqual(len(older), 1024)
        self.assertEqual(len(current), 1)
        self.assertEqual(current[0][3:6], ("new", "saf-new", "PENDING"))

    def test_only_same_source_exact_restored_context_is_rearmed(self):
        self.seed("stale-source", "device-old", state="STALE_SOURCE")
        self.seed("stale-context", "device-old", state="STALE_CONTEXT")
        self.bind("stale-context")
        self.seed("ready", "device-old", state="READY")
        self.seed("cooling", "device-old", state="PENDING", at=900)
        for album, expected in [("stale-source", 0), ("stale-context", 1),
                                ("ready", 0), ("cooling", 0)]:
            self.assertEqual(self.query("reviveExactContextOnCatalogDelta",
                libraryId="lib", creditKey="artist", associationId="assoc",
                albumKey=album, sourceId="device-old", nowMs=50).rowcount, expected)
        self.assertEqual(self.row("stale-source"), ("device-old", "STALE_SOURCE", 1))
        self.assertEqual(self.row("stale-context"), ("device-old", "PENDING", 50))
        self.assertEqual(self.query("reviveExactContextOnCatalogDelta",
            libraryId="lib", creditKey="artist", associationId="assoc",
            albumKey="stale-context", sourceId="saf-new", nowMs=60).rowcount, 0)

    def test_explicit_missing_art_rearms_ready_but_not_cooldown_or_identity(self):
        states = ["READY", "CACHED", "PENDING", "NEEDS_IDENTITY", "STALE_CONTEXT"]
        for state in states:
            self.seed(state, "saf-new", state=state, at=900)
            if state in ("READY", "CACHED"):
                self.bind(state)  # explicit proof is now mandatory for rearming
            count = self.query("rearmCompletedForExplicitMissingArtwork",
                libraryId="lib", creditKey="artist", associationId="assoc",
                albumKey=state, sourceId="saf-new", nowMs=20).rowcount
            self.assertEqual(count, 1 if state in ("READY", "CACHED") else 0, state)
        self.assertEqual(self.row("READY"), ("saf-new", "PENDING", 20))
        self.assertEqual(self.row("CACHED"), ("saf-new", "PENDING", 20))
        self.assertEqual(self.row("PENDING"), ("saf-new", "PENDING", 900))
        self.assertEqual(self.row("NEEDS_IDENTITY"), ("saf-new", "NEEDS_IDENTITY", 900))

    def test_source_filtered_pending_never_includes_other_root_even_same_artist(self):
        self.seed("same-artist-old", "device-old")
        self.seed("same-artist-new", "saf-new")
        self.bind("same-artist-new")
        rows = self.query("pendingForRoot", libraryId="lib", creditKey="artist",
                          associationId="assoc", sourceId="saf-new", nowMs=5).fetchall()
        self.assertEqual([r[3] for r in rows], ["same-artist-new"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
