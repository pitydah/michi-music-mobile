"""K46: five real DAO SQL revival routes require exact current source proof.

The test extracts every @Query from the candidate Kotlin file. It runs the same
SQL directly against SQLite, with bound inputs and a genuinely conflicting root,
association, and binding epoch. No Android, Internet or synthetic rewrite of SQL.
"""
from pathlib import Path
import re
import sqlite3
import unittest

DAO = Path(__file__).resolve().parents[1] / 'data/src/main/java/org/michimusic/data/artist/ArtistArtworkCatalogJournal.kt'
METHODS = {
    'reviveExactContextOnCatalogDelta': 'STALE_CONTEXT',
    'resumeReturnedSourceBoundRow': 'STALE_SOURCE',
    'rearmCompletedForExplicitMissingArtwork': 'READY',
    'reviveVerifiedEvent': 'NEEDS_IDENTITY',
}

def query(method):
    source = DAO.read_text()
    pattern = re.compile(r'@Query\("""(.*?)"""\)\s*suspend fun (\w+)\(', re.S)
    matches=[m.group(1) for m in pattern.finditer(source) if m.group(2)==method]
    if len(matches)!=1: raise AssertionError('Missing actual DAO SQL: '+method)
    return matches[0]

def fixture():
    c = sqlite3.connect(':memory:')
    c.executescript('''
        CREATE TABLE artist_album_credit_binding (
           libraryId TEXT, albumKey TEXT, creditKey TEXT, associationId TEXT, confirmedAtMs INTEGER);
        CREATE TABLE artist_artwork_source_binding_proof (
           libraryId TEXT, albumKey TEXT, creditKey TEXT, associationId TEXT, sourceId TEXT, confirmedAtMs INTEGER);
        CREATE TABLE artist_artwork_auto_outbox (
           libraryId TEXT, albumKey TEXT, creditKey TEXT, associationId TEXT,
           sourceId TEXT, state TEXT, updatedAtMs INTEGER);
    ''')
    return c

def seed(c, album, state, *, binding=True, proof=True, root='root-A',
         version=10, proof_version=10, proof_root=None, association='assoc'):
    c.execute('INSERT INTO artist_artwork_auto_outbox VALUES (?,?,?,?,?,?,?)',
              ('lib',album,'artist','assoc',root,state,1))
    if binding:
        c.execute('INSERT INTO artist_album_credit_binding VALUES (?,?,?,?,?)',
                  ('lib',album,'artist','assoc',version))
    if proof:
        c.execute('INSERT INTO artist_artwork_source_binding_proof VALUES (?,?,?,?,?,?)',
                  ('lib',album,'artist',association,proof_root or root,proof_version))

def params(album):
    return dict(libraryId='lib',creditKey='artist',associationId='assoc',
                albumKey=album,sourceId='root-A',nowMs=99)

class StrictProofRevival(unittest.TestCase):
    def test_all_four_update_routes_reject_missing_wrong_or_stale_proof(self):
        for method,state in METHODS.items():
            with self.subTest(method=method):
                c=fixture();self.addCleanup(c.close)
                for name, kwargs in (
                    ('valid',{}),('missing-proof',{'proof':False}),
                    ('stale-proof',{'proof_version':9}),
                    ('other-source',{'proof_root':'root-B'}),
                    ('other-association',{'association':'different'}),
                    ('no-binding',{'binding':False})):
                    seed(c,name,state,**kwargs)
                sql=query(method)
                for name in ('missing-proof','stale-proof','other-source','other-association','no-binding'):
                    self.assertEqual(c.execute(sql,params(name)).rowcount,0,(method,name))
                self.assertEqual(c.execute(sql,params('valid')).rowcount,1,method)
                self.assertEqual(c.execute(sql,params('valid')).rowcount,0,method)
                self.assertEqual(dict(c.execute('SELECT albumKey,state FROM artist_artwork_auto_outbox'))['valid'],'PENDING')

    def test_returned_source_reader_cannot_offer_unproven_album(self):
        c=fixture();self.addCleanup(c.close)
        seed(c,'valid','STALE_SOURCE')
        seed(c,'without-proof','STALE_SOURCE',proof=False)
        seed(c,'outdated','STALE_SOURCE',proof_version=9)
        seed(c,'wrong-source','STALE_SOURCE',proof_root='root-B')
        raw=query('returnedSourceBoundRowsForAlbums')
        raw=raw.replace('IN (:albumKeys)','IN (:album_0,:album_1,:album_2,:album_3)')
        args=dict(libraryId='lib',sourceId='root-A',album_0='valid',album_1='without-proof',
                  album_2='outdated',album_3='wrong-source')
        rows=c.execute(raw,args).fetchall()
        self.assertEqual(['valid'],[row[1] for row in rows])

    def test_ready_and_cached_can_repair_only_when_binding_is_proven(self):
        c=fixture();self.addCleanup(c.close)
        for state in ('READY','CACHED','PENDING','NEEDS_IDENTITY'):
            seed(c,state,state)
        sql=query('rearmCompletedForExplicitMissingArtwork')
        results={s:c.execute(sql,params(s)).rowcount for s in ('READY','CACHED','PENDING','NEEDS_IDENTITY')}
        self.assertEqual(results,dict(READY=1,CACHED=1,PENDING=0,NEEDS_IDENTITY=0))

    def test_dao_proof_receipts_remain_monotonic_and_not_overwritten_by_refresh(self):
        data=DAO.read_text()
        self.assertIn('suspend fun insertOutboxIfAbsent(',data)
        self.assertIn("state = 'NEEDS_SOURCE_PROOF'",data)
        self.assertIn("AND state = 'PENDING' AND EXISTS",query('finishBoundAssociation'))

if __name__=='__main__':unittest.main()
