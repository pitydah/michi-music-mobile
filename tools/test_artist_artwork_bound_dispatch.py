#!/usr/bin/env python3
"""K31 functional tests of literal Room @Query SQL against native SQLite.
Validates due dispatch, manual retry, and deferred-wake fairness after >128
orphan associations. It does NOT certify Room-generated Kotlin or WorkManager.
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
    raise AssertionError(f'Unknown DAO method: {name}')


class BoundDispatchTests(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.addCleanup(self.db.close)
        self.db.executescript('''
        CREATE TABLE artist_artwork_auto_outbox (
          libraryId TEXT NOT NULL, creditKey TEXT NOT NULL,
          associationId TEXT NOT NULL, albumKey TEXT NOT NULL,
          sourceId TEXT NOT NULL, state TEXT NOT NULL, updatedAtMs INTEGER NOT NULL,
          PRIMARY KEY(libraryId, creditKey, associationId, albumKey, sourceId));
        CREATE TABLE artist_album_credit_binding (
          libraryId TEXT NOT NULL, albumKey TEXT NOT NULL,
          creditKey TEXT NOT NULL, associationId TEXT NOT NULL,
          evidenceType TEXT NOT NULL, confirmedAtMs INTEGER NOT NULL,
          PRIMARY KEY(libraryId,albumKey,creditKey));
        ''')

    def outbox(self, album, *, root='active', credit='artist', assoc='assoc', lib='lib', due=1, state='PENDING'):
        self.db.execute('INSERT INTO artist_artwork_auto_outbox VALUES (?,?,?,?,?,?,?)',
                        (lib, credit, assoc, album, root, state, due))

    def binding(self, album, *, credit='artist', assoc='assoc', lib='lib'):
        self.db.execute('INSERT INTO artist_album_credit_binding VALUES (?,?,?,?,?,?)',
                        (lib, album, credit, assoc, 'USER_CONFIRMED', 1))

    def run_sql(self, name, **params):
        return self.db.execute(query(name), params).fetchall()

    def test_1300_orphans_in_distinct_associations_do_not_starve_one_verified_artist(self):
        for i in range(1300):
            self.outbox(f'orphan-{i}', credit=f'name-{i}', assoc=f'other-{i}')
        self.outbox('real', due=9)
        self.binding('real')
        rows = self.run_sql('boundPendingDispatchForRoot', libraryId='lib', sourceId='active', limit=128, nowMs=10)
        self.assertEqual(rows, [('lib', 'artist', 'assoc', 9)])

    def test_manual_retry_one_eligible_artist_after_200_orphans(self):
        for i in range(200):
            self.outbox(f'orphan-{i}', credit=f'n-{i}', assoc=f'a-{i}')
        self.outbox('good', due=5000)
        self.binding('good')
        rows = self.run_sql('boundManualRetryCandidates', libraryId='lib', sourceId='active', limit=16)
        self.assertEqual(rows, [('lib', 'artist', 'assoc', 5000)])

    def test_deferred_wake_ignores_orphan_future_deadlines(self):
        self.outbox('orphan', due=150)
        self.outbox('bound', due=300)
        self.binding('bound')
        rows = self.run_sql('nextBoundDeferredDueAtForRoot', libraryId='lib', sourceId='active', nowMs=100)
        self.assertEqual(rows, [(300,)])

    def test_stale_orphan_only_never_causes_a_future_wake(self):
        self.outbox('orphan', due=600)
        rows = self.run_sql('nextBoundDeferredDueAtForRoot', libraryId='lib', sourceId='active', nowMs=100)
        self.assertEqual(rows, [(None,)])

    def test_different_library_root_and_association_never_authorize(self):
        self.outbox('root-other', root='former')
        self.binding('root-other')
        self.outbox('library-other', lib='other')
        self.binding('library-other', lib='other')
        self.outbox('same-name-other', assoc='homonym')
        self.binding('same-name-other', assoc='assoc')
        rows = self.run_sql('boundPendingDispatchForRoot', libraryId='lib', sourceId='active', limit=128, nowMs=10)
        self.assertEqual(rows, [])

    def test_grouped_exact_album_bindings_fairness(self):
        for i in range(190):
            self.outbox(f'a-{i}', due=1)
            self.binding(f'a-{i}')
        self.outbox('other', credit='second', assoc='second', due=2)
        self.binding('other', credit='second', assoc='second')
        rows = self.run_sql('boundPendingDispatchForRoot', libraryId='lib', sourceId='active', limit=16, nowMs=10)
        self.assertEqual(len(rows), 2)
        self.assertEqual({r[2] for r in rows}, {'assoc', 'second'})

    def test_cooldown_and_ready_never_enter_due_dispatch(self):
        self.outbox('future', due=200)
        self.binding('future')
        self.outbox('done', due=1, state='READY', credit='done', assoc='done')
        self.binding('done', credit='done', assoc='done')
        self.outbox('due', due=2, credit='due', assoc='due')
        self.binding('due', credit='due', assoc='due')
        rows = self.run_sql('boundPendingDispatchForRoot', libraryId='lib', sourceId='active', limit=16, nowMs=100)
        self.assertEqual([r[2] for r in rows], ['due'])

    def test_manual_retry_ignores_terminal_results_even_when_bound(self):
        for state in ('READY','CACHED','NEEDS_IDENTITY','STALE_SOURCE'):
            self.outbox(state, state=state, credit=state, assoc=state)
            self.binding(state, credit=state, assoc=state)
        rows = self.run_sql('boundManualRetryCandidates', libraryId='lib', sourceId='active', limit=16)
        self.assertEqual(rows, [])

    def test_sql_source_is_actual_candidate_method_used_by_scheduler(self):
        scheduler = (DAO.parents[8] / 'app/src/main/java/org/michimusic/mobile/artist/artwork/ArtistArtworkAutoScheduler.kt')
        text = scheduler.read_text()
        self.assertIn('journal.provenPendingDispatchPageForRoot(', text)
        self.assertIn('journal.provenNextDeferredDueAtForRoot(', text)
        self.assertIn('dao.provenManualRetryPageForRoot(', text)

if __name__=='__main__':
    unittest.main(verbosity=2)
