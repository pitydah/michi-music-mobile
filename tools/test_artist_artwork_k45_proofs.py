"""K45 regression: execute the production Room SQL with real SQLite.

The test uses the exact @Query text extracted from the candidate Kotlin DAO,
not a manually re-implemented Python equivalent. No Android runtime required.
"""
import pathlib
import re
import sqlite3
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
DAO = ROOT / 'data/src/main/java/org/michimusic/data/artist/ArtistArtworkCatalogJournal.kt'
ROOM_TEST = ROOT / 'data/src/test/java/org/michimusic/data/artist/ArtistArtworkAutomatedCommitTest.kt'


def finish_sql():
    source = DAO.read_text()
    matches = re.findall(
        r'@Query\("""(UPDATE artist_artwork_auto_outbox SET state = :state, updatedAtMs = :nowMs.*?)"""\)\s*suspend fun finishBoundAssociation\(',
        source, re.S,
    )
    if len(matches) != 1:
        raise AssertionError(f'Expected one production SQL, found {len(matches)}')
    return matches[0]


def db():
    conn = sqlite3.connect(':memory:')
    conn.executescript('''
        CREATE TABLE artist_artwork_auto_outbox (
            libraryId TEXT, creditKey TEXT, associationId TEXT,
            albumKey TEXT, sourceId TEXT, state TEXT, updatedAtMs INTEGER
        );
        CREATE TABLE artist_album_credit_binding (
            libraryId TEXT, creditKey TEXT, associationId TEXT,
            albumKey TEXT, confirmedAtMs INTEGER
        );
        CREATE TABLE artist_artwork_source_binding_proof (
            libraryId TEXT, creditKey TEXT, associationId TEXT,
            albumKey TEXT, sourceId TEXT, confirmedAtMs INTEGER
        );
    ''')
    return conn


def seed(conn, album, root='root-A', state='PENDING',
         binding=True, proof=False, confirmed=10, proof_version=None):
    conn.execute('INSERT INTO artist_artwork_auto_outbox VALUES (?,?,?,?,?,?,?)',
                 ('lib','artist','assoc',album,root,state,1))
    if binding:
        conn.execute('INSERT INTO artist_album_credit_binding VALUES (?,?,?,?,?)',
                     ('lib','artist','assoc',album,confirmed))
    if proof:
        conn.execute('INSERT INTO artist_artwork_source_binding_proof VALUES (?,?,?,?,?,?)',
                     ('lib','artist','assoc',album,root,
                      confirmed if proof_version is None else proof_version))


class ProofBoundCompletion(unittest.TestCase):
    def test_terminal_state_updates_only_exact_root_and_matching_proof_version(self):
        c = db()
        seed(c,'confirmed', proof=True)
        seed(c,'bound-without-proof')
        seed(c,'proof-superseded', proof=True, proof_version=9)
        seed(c,'other-root',root='root-B',proof=True)
        seed(c,'orphan',binding=False)
        seed(c,'completed',state='READY',proof=True)
        params = {'libraryId':'lib','creditKey':'artist','associationId':'assoc',
                  'expectedSource':'root-A','state':'READY','nowMs':42}
        r = c.execute(finish_sql(),params)
        self.assertEqual(1,r.rowcount)
        states = dict(c.execute('SELECT albumKey,state FROM artist_artwork_auto_outbox'))
        self.assertEqual('READY', states['confirmed'])
        self.assertEqual('PENDING',states['bound-without-proof'])
        self.assertEqual('PENDING',states['proof-superseded'])
        self.assertEqual('PENDING',states['other-root'])
        self.assertEqual('PENDING',states['orphan'])
        self.assertEqual('READY',states['completed'])
        self.assertEqual(0, c.execute(finish_sql(),params).rowcount)

    def test_same_album_different_association_or_source_does_not_borrow_proof(self):
        c = db()
        seed(c,'song',proof=False)
        c.execute('INSERT INTO artist_artwork_source_binding_proof VALUES (?,?,?,?,?,?)',
                  ('lib','artist','different-assoc','song','root-A',10))
        c.execute('INSERT INTO artist_artwork_source_binding_proof VALUES (?,?,?,?,?,?)',
                  ('lib','artist','assoc','song','root-B',10))
        self.assertEqual(0,c.execute(finish_sql(),
            {'libraryId':'lib','creditKey':'artist','associationId':'assoc',
             'expectedSource':'root-A','state':'CACHED','nowMs':33}).rowcount)

    def test_fixture_seeds_exact_proof_and_uses_full_dao_signature(self):
        source = ROOM_TEST.read_text()
        self.assertIn('captureBindingSourceProofs("lib", "artist", "assoc", source,',source)
        self.assertIn('"album-a", source, "STALE_SOURCE", 2)',source)
        self.assertIn('boundAlbumWithoutSourceProofRemainsPendingOnOtherAlbumCompletion',source)


if __name__=='__main__':
    unittest.main()
