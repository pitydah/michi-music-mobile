#!/usr/bin/env python3
"""K32 functional SQL contract for independent per-source outbox and A -> B -> A.

SQLite runs literal @Query statements copied from the candidate Kotlin DAO, not
handwritten approximations. Android/Room/KSP certification remains NOT_RUN.
"""
from pathlib import Path
import re
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[1]
DAO = ROOT / 'data/src/main/java/org/michimusic/data/artist/ArtistArtworkCatalogJournal.kt'
SCHEMA = ROOT / 'data/src/main/java/org/michimusic/data/artist/ArtistEnrichmentCache.kt'


def query(name):
    data = DAO.read_text()
    regex = r'@Query\((?:"""(?P<block>.*?)"""|"(?P<inline>[^"\n]*)")\)\s*suspend fun (?P<name>\w+)\('
    for item in re.finditer(regex, data, re.S):
        if item.group('name') == name:
            return (item.group('block') or item.group('inline')).strip()
    raise AssertionError('Missing DAO SQL for ' + name)


class ReturnedRootContract(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.addCleanup(self.db.close)
        self.db.executescript('''
        CREATE TABLE artist_artwork_auto_outbox (
          libraryId TEXT NOT NULL, creditKey TEXT NOT NULL, associationId TEXT NOT NULL,
          albumKey TEXT NOT NULL, sourceId TEXT NOT NULL, state TEXT NOT NULL,
          updatedAtMs INTEGER NOT NULL,
          PRIMARY KEY(libraryId, creditKey, associationId, albumKey, sourceId));
        CREATE TABLE artist_album_credit_binding (
          libraryId TEXT NOT NULL, albumKey TEXT NOT NULL, creditKey TEXT NOT NULL,
          associationId TEXT NOT NULL, confirmedAtMs INTEGER NOT NULL,
          PRIMARY KEY(libraryId, albumKey, creditKey));
        CREATE TABLE artist_artwork_source_binding_proof (
          libraryId TEXT NOT NULL, albumKey TEXT NOT NULL,
          creditKey TEXT NOT NULL, associationId TEXT NOT NULL,
          sourceId TEXT NOT NULL, confirmedAtMs INTEGER NOT NULL);
        ''')

    def row(self, source, album='album', credit='artist', assoc='assoc', lib='lib'):
        return self.db.execute('''SELECT state,updatedAtMs FROM artist_artwork_auto_outbox
          WHERE libraryId=? AND creditKey=? AND associationId=? AND albumKey=? AND sourceId=?''',
          (lib,credit,assoc,album,source)).fetchone()

    def seed(self, source, album='album', state='PENDING', due=1, credit='artist', assoc='assoc',lib='lib'):
        self.db.execute('INSERT INTO artist_artwork_auto_outbox VALUES (?,?,?,?,?,?,?)',
                        (lib,credit,assoc,album,source,state,due))

    def bind(self, album='album', credit='artist', assoc='assoc',lib='lib'):
        self.db.execute('INSERT INTO artist_album_credit_binding VALUES (?,?,?,?,1)',
                        (lib,album,credit,assoc))
        # This is an AUTHORISED test fixture. The old test seeded a mere textual
        # album binding, incorrectly treating it as proof of the source root.
        self.db.execute("""INSERT INTO artist_artwork_source_binding_proof
             SELECT DISTINCT libraryId,albumKey,creditKey,associationId,sourceId,1
             FROM artist_artwork_auto_outbox
             WHERE libraryId=? AND albumKey=? AND creditKey=? AND associationId=?""",
             (lib,album,credit,assoc))

    def execute(self, name, **args):
        sql = query(name)
        # Room expands List<String> parameters in SQL IN() automatically. sqlite3
        # itself requires scalar placeholders; model that expansion exactly here.
        if 'albumKeys' in args:
            albums = args.pop('albumKeys')
            if not albums: raise ValueError('Empty list is not valid for this fixture')
            slots = [f'albumKey_{i}' for i in range(len(albums))]
            sql = sql.replace('IN (:albumKeys)', 'IN (' + ','.join(':'+key for key in slots) + ')')
            args.update(zip(slots, albums))
        return self.db.execute(sql,args)

    def test_unique_key_has_source_in_room_entity_and_actual_migration(self):
        entity = DAO.read_text()
        migration = SCHEMA.read_text()
        self.assertIn('primaryKeys = ["libraryId", "creditKey", "associationId", "albumKey", "sourceId"]',entity)
        self.assertIn('PRIMARY KEY(`libraryId`, `creditKey`, `associationId`, `albumKey`, `sourceId`)',migration)
        self.assertEqual([x[1] for x in self.db.execute('PRAGMA table_info(artist_artwork_auto_outbox)').fetchall() if x[5]],
                         ['libraryId','creditKey','associationId','albumKey','sourceId'])

    def test_real_migration_sql_creates_scoped_pk_without_dropping_identity(self):
        body = SCHEMA.read_text().split('val MIGRATION_10_11 = Migration(10, 11) { db ->', 1)[1].split(
            'private fun membershipFingerprint', 1)[0]
        statements = re.findall(r'db\.execSQL\("""(.*?)"""\.trimIndent\(\)\)', body, re.S)
        self.assertEqual(len(statements), 11)
        db = sqlite3.connect(':memory:')
        self.addCleanup(db.close)
        db.execute('CREATE TABLE artist_enrichment (libraryId TEXT, confirmedMbid TEXT)')
        db.execute("INSERT INTO artist_enrichment VALUES ('lib', 'confirmed-stays')")
        for statement in statements:
            db.execute(statement)
        pk = sorted(((row[5], row[1]) for row in db.execute(
            "PRAGMA table_info('artist_artwork_auto_outbox')") if row[5]), key=lambda item: item[0])
        self.assertEqual([name for _, name in pk],
            ['libraryId', 'creditKey', 'associationId', 'albumKey', 'sourceId'])
        self.assertEqual(db.execute('SELECT confirmedMbid FROM artist_enrichment').fetchone(),
                         ('confirmed-stays',))

    def test_same_album_and_credit_in_two_roots_are_independent(self):
        self.seed('root-A',state='STALE_SOURCE')
        self.seed('root-B',state='PENDING')
        self.bind()
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM artist_artwork_auto_outbox').fetchone(),(2,))
        a=self.execute('returnedSourceBoundRowsForAlbums',libraryId='lib',sourceId='root-A',albumKeys=['album']).fetchall()
        self.assertEqual(len(a),1)
        self.assertEqual(a[0][4:6],('root-A','STALE_SOURCE'))
        self.assertEqual(self.execute('resumeReturnedSourceBoundRow',libraryId='lib', creditKey='artist',associationId='assoc',albumKey='album',sourceId='root-A',nowMs=20).rowcount,1)
        self.assertEqual(self.row('root-A'),('PENDING',20))
        self.assertEqual(self.row('root-B'),('PENDING',1))
        self.assertEqual(self.execute('invalidateOtherSources',libraryId='lib',sourceId='root-A',nowMs=21).rowcount,1)
        self.assertEqual(self.row('root-B'),('STALE_SOURCE',21))
        self.assertEqual(self.execute('resumeReturnedSourceBoundRow',libraryId='lib',creditKey='artist', associationId='assoc',albumKey='album',sourceId='root-B',nowMs=31).rowcount,1)
        self.assertEqual(self.row('root-A'),('PENDING',20))
        self.assertEqual(self.row('root-B'),('PENDING',31))

    def test_returned_root_must_still_have_exact_album_credit_binding(self):
        self.seed('root-A', state='STALE_SOURCE')
        self.assertEqual(self.execute('returnedSourceBoundRowsForAlbums',libraryId='lib',sourceId='root-A',albumKeys=['album']).fetchall(),[])
        self.assertEqual(self.execute('resumeReturnedSourceBoundRow',libraryId='lib', creditKey='artist', associationId='assoc',albumKey='album',sourceId='root-A',nowMs=10).rowcount,0)
        self.bind(assoc='wrong-homonym')
        self.assertEqual(self.execute('returnedSourceBoundRowsForAlbums',libraryId='lib',sourceId='root-A',albumKeys=['album']).fetchall(),[])
        self.assertEqual(self.row('root-A'),('STALE_SOURCE',1))

    def test_other_album_or_root_is_not_resumed_by_a_requested_page(self):
        self.seed('root-A','album-1',state='STALE_SOURCE')
        self.seed('root-A','album-2',state='STALE_SOURCE')
        self.bind('album-1');self.bind('album-2')
        rows=self.execute('returnedSourceBoundRowsForAlbums',libraryId='lib',sourceId='root-A',albumKeys=['album-1']).fetchall()
        self.assertEqual([x[3] for x in rows],['album-1'])
        self.assertEqual(self.execute('resumeReturnedSourceBoundRow',libraryId='lib',creditKey='artist',associationId='assoc',albumKey=rows[0][3],sourceId='root-A',nowMs=50).rowcount,1)
        self.assertEqual(self.row('root-A','album-2'),('STALE_SOURCE',1))

    def test_ready_deferred_and_identity_receipts_never_reopen_from_source_return(self):
        for state in ('READY','CACHED','PENDING','NEEDS_IDENTITY','STALE_CONTEXT'):
            self.seed('root-A',state,state=state,due=700)
            self.bind(state)
            self.assertEqual(self.execute('resumeReturnedSourceBoundRow', libraryId='lib', creditKey='artist',associationId='assoc',albumKey=state,sourceId='root-A',nowMs=50).rowcount,0)
            self.assertEqual(self.row('root-A',state),(state,700))

    def test_exact_context_delta_and_explicit_repair_never_steal_other_root(self):
        self.seed('root-A',state='STALE_CONTEXT')
        self.seed('root-B',state='STALE_CONTEXT')
        self.bind()
        self.assertEqual(self.execute('reviveExactContextOnCatalogDelta',libraryId='lib',creditKey='artist',associationId='assoc',albumKey='album',sourceId='root-B',nowMs=50).rowcount,1)
        self.assertEqual(self.row('root-A'),('STALE_CONTEXT',1))
        self.assertEqual(self.row('root-B'),('PENDING',50))
        self.seed('root-A','ready',state='READY');self.bind('ready')
        self.seed('root-B','ready',state='READY')
        self.db.execute("""INSERT INTO artist_artwork_source_binding_proof
            VALUES ('lib','ready','artist','assoc','root-B',1)""")
        self.assertEqual(self.execute('rearmCompletedForExplicitMissingArtwork',libraryId='lib',creditKey='artist',associationId='assoc',albumKey='ready',sourceId='root-B',nowMs=80).rowcount,1)
        self.assertEqual(self.row('root-A','ready'),('READY',1))
        self.assertEqual(self.row('root-B','ready'),('PENDING',80))

    def test_first_bound_album_ignores_old_root_event(self):
        self.seed('root-A',state='READY')
        self.bind()
        self.assertEqual(self.execute('firstBoundAlbumKey',libraryId='lib',creditKey='artist',associationId='assoc',sourceId='root-B').fetchone(),('album',))
        self.assertEqual(self.execute('firstBoundAlbumKey',libraryId='lib',creditKey='artist',associationId='assoc',sourceId='root-A').fetchone(),None)

    def test_source_return_selection_cannot_reopen_absent_observed_credit(self):
        """SQL returns candidate ROWS; the processor must explicitly intersect observation pairs."""
        processor=(ROOT/'app/src/main/java/org/michimusic/mobile/artist/artwork/ArtistArtworkCatalogProcessor.kt').read_text()
        self.assertIn('it.albumKey to it.creditKey in observedPairs',processor)
        self.assertIn('journal.baseline(libraryId) != baseline',processor)
        self.assertIn('MusicRootPreferences(app).currentRootStamp() != enteredRoot',processor)


if __name__ == '__main__':
    unittest.main()
