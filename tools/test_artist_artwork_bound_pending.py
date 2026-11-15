#!/usr/bin/env python3
"""K30 behavioral test of the *actual* outbox @Query statements in SQLite.

This checks exact binding filtering and orphan cleanup across 1,300 stale entries.
It is not Room/KSP certification or a real WorkManager/device test.
"""
from pathlib import Path
import re
import sqlite3
import unittest

DAO = Path(__file__).resolve().parents[1] / 'data/src/main/java/org/michimusic/data/artist/ArtistArtworkCatalogJournal.kt'

def query(name):
    source = DAO.read_text()
    pattern = r'@Query\((?:"""(?P<block>.*?)"""|"(?P<inline>[^"\n]*)")\)\s*suspend fun (?P<name>\w+)\('
    for match in re.finditer(pattern, source, re.S):
        if match.group('name') == name:
            return (match.group('block') or match.group('inline')).strip()
    raise AssertionError('Missing DAO query '+name)

class ExactBindingDispatchTests(unittest.TestCase):
    def setUp(self):
        self.db=sqlite3.connect(':memory:')
        self.addCleanup(self.db.close)
        self.db.executescript('''
        CREATE TABLE artist_artwork_auto_outbox (
          libraryId TEXT NOT NULL, creditKey TEXT NOT NULL, associationId TEXT NOT NULL,
          albumKey TEXT NOT NULL, sourceId TEXT NOT NULL, state TEXT NOT NULL,
          updatedAtMs INTEGER NOT NULL,
          PRIMARY KEY(libraryId, creditKey, associationId, albumKey, sourceId));
        CREATE TABLE artist_album_credit_binding (
          libraryId TEXT NOT NULL, albumKey TEXT NOT NULL,
          creditKey TEXT NOT NULL, associationId TEXT NOT NULL,
          confirmedAtMs INTEGER NOT NULL, PRIMARY KEY(libraryId,albumKey,creditKey));
        ''')
    def outbox(self,album,root='active',credit='artist',assoc='good',library='lib',due=1,state='PENDING'):
        self.db.execute('INSERT INTO artist_artwork_auto_outbox VALUES (?,?,?,?,?,?,?)',
                        (library,credit,assoc,album,root,state,due))
    def bind(self,album,credit='artist',assoc='good',library='lib'):
        self.db.execute('INSERT INTO artist_album_credit_binding VALUES (?,?,?,?,1)',
                        (library,album,credit,assoc))
    def run_sql(self,name,**kw):
        return self.db.execute(query(name),kw)
    def args(self,**kw):
        return dict(dict(libraryId='lib',creditKey='artist',associationId='good',sourceId='active',nowMs=50),**kw)
    def test_1300_orphans_cannot_hide_one_verified_due_album(self):
        for i in range(1300):self.outbox(f'orphan-{i:05d}')
        self.outbox('genuine',due=5);self.bind('genuine')
        rows=self.run_sql('pendingForRoot',**self.args()).fetchall()
        self.assertEqual([r[3] for r in rows], ['genuine'])
        self.assertEqual(self.run_sql('finishOrphanedPendingForRoot',**self.args()).rowcount,1300)
        self.assertEqual(self.run_sql('pendingForRoot',**self.args()).fetchall(),rows)
    def test_cleanup_stays_in_original_source_and_preserves_other_libraries(self):
        self.outbox('old-source',root='other')
        self.outbox('old-library',library='other-library')
        self.outbox('active-orphan')
        self.assertEqual(self.run_sql('finishOrphanedPendingForRoot',**self.args()).rowcount,1)
        self.assertEqual(self.db.execute("SELECT albumKey FROM artist_artwork_auto_outbox WHERE state='PENDING'").fetchall(),
                         [('old-source',),('old-library',)])
    def test_wrong_credit_and_association_do_not_authorize_an_album(self):
        self.outbox('other-artist');self.bind('other-artist',credit='other')
        self.outbox('homonym');self.bind('homonym',assoc='not-this-association')
        self.assertEqual(self.run_sql('pendingForRoot',**self.args()).fetchall(),[])
        self.assertEqual(self.run_sql('finishOrphanedPendingForRoot',**self.args()).rowcount,2)
    def test_due_future_does_not_block_current_work(self):
        self.outbox('future',due=500);self.bind('future')
        self.outbox('now',due=2);self.bind('now')
        self.assertEqual([r[3] for r in self.run_sql('pendingForRoot',**self.args()).fetchall()],['now'])
        self.assertEqual(self.run_sql('finishOrphanedPendingForRoot',**self.args()).rowcount,0)
        self.assertEqual([r[3] for r in self.run_sql('pendingForRoot',**self.args(nowMs=501)).fetchall()],['now','future'])
    def test_manual_ready_and_completed_rows_not_rewritten_by_orphan_cleanup(self):
        for state in ['READY','CACHED','NEEDS_IDENTITY','STALE_SOURCE','STALE_CONTEXT']:
            self.outbox(state,state=state)
        self.assertEqual(self.run_sql('finishOrphanedPendingForRoot',**self.args()).rowcount,0)
        self.assertEqual(self.db.execute("SELECT COUNT(*) FROM artist_artwork_auto_outbox WHERE state='STALE_CONTEXT'").fetchone(),(1,))
    def test_binding_removed_while_work_waits_becomes_stale(self):
        self.outbox('album');self.bind('album')
        self.assertEqual(len(self.run_sql('pendingForRoot',**self.args()).fetchall()),1)
        self.db.execute("DELETE FROM artist_album_credit_binding WHERE albumKey='album'")
        self.assertEqual(self.run_sql('pendingForRoot',**self.args()).fetchall(),[])
        self.assertEqual(self.run_sql('finishOrphanedPendingForRoot',**self.args()).rowcount,1)
    def test_cleanup_is_idempotent_and_preserves_valid_work(self):
        self.outbox('orphan');self.outbox('bound');self.bind('bound')
        self.assertEqual(self.run_sql('finishOrphanedPendingForRoot',**self.args()).rowcount,1)
        self.assertEqual(self.run_sql('finishOrphanedPendingForRoot',**self.args(nowMs=80)).rowcount,0)
        self.assertEqual([r[3] for r in self.run_sql('pendingForRoot',**self.args()).fetchall()],['bound'])
    def test_all_unbound_returns_no_provider_candidates(self):
        for i in range(1200):self.outbox(f'old-{i}')
        self.assertEqual(self.run_sql('pendingForRoot',**self.args()).fetchall(),[])
        self.assertEqual(self.run_sql('finishOrphanedPendingForRoot',**self.args()).rowcount,1200)

if __name__=='__main__':unittest.main(verbosity=2)
