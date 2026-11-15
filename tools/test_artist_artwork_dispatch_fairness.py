"""K38 integration contract / actual Room @Query SQLite, no Android claims."""
import sqlite3
import unittest
from pathlib import Path
from test_artist_artwork_bound_dispatch import query

ROOT = Path(__file__).resolve().parents[1]
SCHEDULER = ROOT / 'app/src/main/java/org/michimusic/mobile/artist/artwork/ArtistArtworkAutoScheduler.kt'
POLICY = ROOT / 'app/src/main/java/org/michimusic/mobile/artist/artwork/ArtistArtworkDispatchFairness.kt'


class FairDispatchTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.addCleanup(self.db.close)
        self.db.executescript('''
            CREATE TABLE artist_artwork_auto_outbox (
                libraryId TEXT, creditKey TEXT, associationId TEXT,
                albumKey TEXT, sourceId TEXT, state TEXT, updatedAtMs INTEGER);
            CREATE TABLE artist_album_credit_binding (
                libraryId TEXT, albumKey TEXT, creditKey TEXT, associationId TEXT,
                confirmedAtMs INTEGER);
            CREATE TABLE artist_artwork_source_binding_proof (
                libraryId TEXT, sourceId TEXT, albumKey TEXT, creditKey TEXT,
                associationId TEXT, confirmedAtMs INTEGER);
        ''')
        self.sql = query('provenPendingDispatchPageForRoot')

    def insert(self, i, *, due=1, root='s', proof=True, bound=True, assoc=None):
        credit = f'credit-{i:05d}'
        association = assoc or f'assoc-{i:05d}'
        album = f'album-{i:05d}'
        self.db.execute('INSERT INTO artist_artwork_auto_outbox VALUES (?,?,?,?,?,?,?)',
                        ('lib', credit, association, album, root, 'PENDING', due))
        if bound:
            self.db.execute('INSERT INTO artist_album_credit_binding VALUES (?,?,?,?,?)',
                            ('lib', album, credit, association, 99))
        if proof:
            self.db.execute('INSERT INTO artist_artwork_source_binding_proof VALUES (?,?,?,?,?,?)',
                            ('lib', root, album, credit, association, 99))

    def page(self, *, ts=-1, credit='', assoc='', now=100, limit=128):
        return self.db.execute(self.sql, {'libraryId':'lib', 'sourceId':'s',
                       'nowMs':now, 'limit':limit, 'afterReadyAtMs':ts,
                       'afterCreditKey':credit, 'afterAssociationId':assoc}).fetchall()

    def test_more_than_128_active_jobs_do_not_hide_later_verified_artists(self):
        for i in range(400): self.insert(i, due=12)
        active = {(f'credit-{i:05d}', f'assoc-{i:05d}') for i in range(145)}
        cursor = (-1, '', '')
        emitted = []
        for page_num in range(8):
            rows = self.page(ts=cursor[0], credit=cursor[1], assoc=cursor[2])
            for lib, credit, association, due in rows:
                cursor = (due, credit, association)
                if (credit, association) not in active:
                    emitted.append((credit, association))
                    if len(emitted) == 16: break
            if len(emitted) == 16 or len(rows) < 128: break
        self.assertEqual(16, len(emitted))
        self.assertEqual([f'credit-{i:05d}' for i in range(145,161)], [x[0] for x in emitted])

    def test_tied_timestamps_and_two_homonyms_have_stable_order(self):
        self.insert(1, due=100, assoc='association-a')
        self.db.execute("INSERT INTO artist_artwork_auto_outbox SELECT libraryId, creditKey, 'association-b', 'album-extra', sourceId, state, updatedAtMs FROM artist_artwork_auto_outbox")
        self.db.execute("INSERT INTO artist_album_credit_binding VALUES ('lib','album-extra','credit-00001','association-b',99)")
        self.db.execute("INSERT INTO artist_artwork_source_binding_proof VALUES ('lib','s','album-extra','credit-00001','association-b',99)")
        one = self.page(limit=1)
        self.assertEqual([('lib','credit-00001','association-a',100)], one)
        two = self.page(ts=one[0][3], credit=one[0][1], assoc=one[0][2],limit=1)
        self.assertEqual([('lib','credit-00001','association-b',100)],two)
        self.assertEqual([], self.page(ts=two[0][3],credit=two[0][1],assoc=two[0][2],limit=1))

    def test_unbound_legacy_other_root_and_bad_version_do_not_stall_keyset(self):
        for i in range(250): self.insert(i, proof=False)
        for i in range(250,300): self.insert(i, bound=False)
        for i in range(300,350): self.insert(i,root='another')
        self.insert(400,due=12)
        self.assertEqual([('lib','credit-00400','assoc-00400',12)],self.page())
        self.db.execute("UPDATE artist_artwork_source_binding_proof SET confirmedAtMs=100 WHERE albumKey='album-00400'")
        self.assertEqual([], self.page())

    def test_manual_retry_after_140_active_reaches_unqueued_cooldown_jobs(self):
        sql = query('provenManualRetryPageForRoot')
        for i in range(310): self.insert(i, due=50000)
        cursor = (-1, '', '')
        activated = []
        for page_number in range(8):
            rows = self.db.execute(sql, {'libraryId':'lib','sourceId':'s','limit':128,
                'afterReadyAtMs':cursor[0], 'afterCreditKey':cursor[1],
                'afterAssociationId':cursor[2]}).fetchall()
            for _lib, credit, assoc, ready in rows:
                cursor = (ready,credit,assoc)
                if int(credit.rsplit('-',1)[1]) >= 140:
                    activated.append(credit)
                    if len(activated) >= 16: break
            if len(activated) == 16 or len(rows) < 128: break
        self.assertEqual([f'credit-{i:05d}' for i in range(140,156)],activated)

    def test_manual_retry_excludes_other_root_unproven_and_done(self):
        sql = query('provenManualRetryPageForRoot')
        self.insert(1,due=5000)
        self.insert(2,due=4000,proof=False)
        self.insert(3,due=4000,root='other')
        self.insert(4,due=4000)
        self.db.execute("UPDATE artist_artwork_auto_outbox SET state='READY' WHERE albumKey='album-00004'")
        result = self.db.execute(sql, {'libraryId':'lib','sourceId':'s','limit':100,
            'afterReadyAtMs':-1,'afterCreditKey':'','afterAssociationId':''}).fetchall()
        self.assertEqual([('lib','credit-00001','assoc-00001',5000)],result)

    def test_cursor_only_emits_due_not_future_or_done(self):
        self.insert(1, due=10)
        self.insert(2, due=500)
        self.insert(3, due=5)
        self.db.execute("UPDATE artist_artwork_auto_outbox SET state='READY' WHERE albumKey='album-00003'")
        self.assertEqual([('lib','credit-00001','assoc-00001',10)],self.page())

    def test_workmanager_snapshot_and_generation_tag_guards_are_wired(self):
        code = SCHEDULER.read_text()
        self.assertIn('getWorkInfos(activeQuery).get(5, TimeUnit.SECONDS)',code)
        self.assertIn('WorkQuery.Builder.fromTags(listOf(TAG))',code)
        self.assertIn('WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED',code)
        self.assertNotIn('getWorkInfosByTag(TAG)',code)
        self.assertIn('ArtistArtworkDispatchFairness.eligible(tag, active, emptySet())',code)
        self.assertIn('ArtistArtworkDispatchFairness.activeTag(name, rootStamp.epoch)',code)
        self.assertIn('.addTag(requireNotNull(ArtistArtworkDispatchFairness.activeTag',code)
        self.assertIn('provenPendingDispatchPageForRoot(',code)
        self.assertIn('provenManualRetryPageForRoot(',code)
        self.assertIn('if (accepted)',code)
        self.assertNotIn('PeriodicWorkRequest',code)

    def test_bounded_runtime_constants_are_visible_in_policy(self):
        text = POLICY.read_text()
        self.assertIn('MAX_PAGE_SIZE = 128',text)
        self.assertIn('MAX_PAGES = 8',text)
        self.assertIn('MAX_NEW_WORK = 16',text)
        self.assertIn('RUNNING',text)


if __name__ == '__main__':unittest.main()
