#!/usr/bin/env python3
"""K33: execute literal candidate Room SQL for source-specific artist binding proof.

This test does NOT replace Room/KSP or Android WorkManager certification.
"""
from pathlib import Path
import re
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[1]
JOURNAL = ROOT / 'data/src/main/java/org/michimusic/data/artist/ArtistArtworkCatalogJournal.kt'
CACHE = ROOT / 'data/src/main/java/org/michimusic/data/artist/ArtistEnrichmentCache.kt'


def query(name):
    src = JOURNAL.read_text()
    pattern = r'@Query\((?:"""(?P<block>.*?)"""|"(?P<inline>[^"\n]*)")\)\s*suspend fun (?P<name>\w+)\('
    matches = [m for m in re.finditer(pattern, src, re.S) if m.group('name') == name]
    if len(matches) != 1:
        raise AssertionError(f'Expected exactly one @Query({name}), got {len(matches)}')
    return (matches[0].group('block') or matches[0].group('inline')).strip()


def migration_statements():
    body = CACHE.read_text().split('val MIGRATION_10_11 = Migration(10, 11) { db ->', 1)[1]
    body = body.split('private fun membershipFingerprint', 1)[0]
    return re.findall(r'db\.execSQL\("""(.*?)"""\.trimIndent\(\)\)', body, re.S)


class SourceProvenanceSQL(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.addCleanup(self.db.close)
        self.db.executescript('''
            CREATE TABLE artist_album_credit_binding (
                libraryId TEXT NOT NULL, albumKey TEXT NOT NULL,
                creditKey TEXT NOT NULL, associationId TEXT NOT NULL,
                confirmedAtMs INTEGER NOT NULL,
                PRIMARY KEY(libraryId,albumKey,creditKey));
        ''')
        for statement in migration_statements():
            self.db.execute(statement)

    def exec(self, name, **values):
        return self.db.execute(query(name), values)

    def observe(self, source='A', album='album', credit='artist', at=100, library='lib'):
        self.db.execute('''INSERT OR REPLACE INTO artist_artwork_source_catalog_credit
            VALUES (?,?,?,?,?)''', (library,source,album,credit,at))

    def bind(self, album='album', credit='artist', assoc='assoc', at=120, library='lib'):
        self.db.execute('''INSERT OR REPLACE INTO artist_album_credit_binding
            VALUES (?,?,?,?,?)''',(library,album,credit,assoc,at))

    def authorize(self, source='A', assoc='assoc', credit='artist', library='lib', baseline=100, now=130):
        return self.exec('captureBindingSourceProofs',libraryId=library, creditKey=credit,
            associationId=assoc,sourceId=source,baselineAtMs=baseline,nowMs=now)

    def proof(self, source='A', album='album', assoc='assoc', credit='artist', library='lib'):
        return self.exec('countVerifiedSourceBinding', libraryId=library, sourceId=source,
            albumKey=album, creditKey=credit, associationId=assoc).fetchone()[0]

    def event(self, source='A', album='album', assoc='assoc', credit='artist', state='PENDING', at=110,library='lib'):
        self.db.execute('''INSERT INTO artist_artwork_auto_outbox VALUES (?,?,?,?,?,?,?)''',
            (library,credit,assoc,album,source,state,at))

    def dispatch(self, source='A',library='lib', now=250,limit=128):
        return self.exec('provenPendingDispatchForRoot', libraryId=library,sourceId=source,
            nowMs=now,limit=limit).fetchall()

    def test_migration_contains_both_authorization_tables_and_indices(self):
        statements = migration_statements()
        self.assertEqual(len(statements),11)
        for table, pk in (
            ('artist_artwork_source_catalog_credit',['libraryId','sourceId','albumKey','creditKey']),
            ('artist_artwork_source_binding_proof',['libraryId','sourceId','albumKey','creditKey','associationId']),
        ):
            info=self.db.execute(f'PRAGMA table_info({table})').fetchall()
            actual=[name for _,name in sorted((row[5],row[1]) for row in info if row[5])]
            self.assertEqual(actual,pk)
        self.assertIn('ArtistArtworkSourceCatalogCreditRecord::class', CACHE.read_text())
        self.assertIn('ArtistArtworkSourceBindingProofRecord::class', CACHE.read_text())

    def test_exact_confirmed_source_stores_receipt_only_after_observation(self):
        self.observe('A'); self.bind()
        self.authorize('A')
        self.assertEqual(self.proof('A'),1)
        self.assertEqual(self.proof('B'),0)
        self.event('A')
        self.assertEqual(len(self.dispatch('A')),1)
        self.assertEqual(len(self.dispatch('B')),0)

    def test_identical_album_on_other_root_cannot_borrow_source_a(self):
        self.observe('A'); self.bind(); self.authorize('A')
        self.observe('B',at=200)
        self.event('A'); self.event('B')
        self.assertEqual(self.proof('A'),1)
        self.assertEqual(self.proof('B'),0)
        self.assertEqual(len(self.dispatch('A')),1)
        self.assertEqual(self.dispatch('B'),[])
        self.assertEqual(self.exec('countProvenPendingForAssociation',libraryId='lib',
            sourceId='B',creditKey='artist',associationId='assoc',nowMs=999).fetchone(),(0,))

    def test_both_sources_observed_before_binding_fail_closed(self):
        self.observe('A',at=10);self.observe('B',at=11);self.bind(at=20)
        self.authorize('A',baseline=10,now=25)
        self.authorize('B',baseline=11,now=25)
        self.assertEqual(self.proof('A')+self.proof('B'),0)

    def test_legacy_binding_can_be_explictly_reconfirmed_after_source_baseline(self):
        self.bind(at=20)
        self.observe('A',at=100)
        self.authorize('A',baseline=100,now=130)
        self.assertEqual(self.proof('A'),0)
        # A separate, explicit user bind operation advances confirmedAtMs to now.
        self.bind(at=120)
        self.authorize('A',baseline=100,now=130)
        self.assertEqual(self.proof('A'),1)
        self.event('A')
        self.assertEqual(len(self.dispatch('A')),1)
        authority=(ROOT/'app/src/main/java/org/michimusic/mobile/artist/ArtistEnrichmentPersistence.kt').read_text()
        semantic=authority.split('private suspend fun bindAlbumsLocked(',1)[1].split('override suspend fun replaceAlbumBindings(',1)[0]
        self.assertIn('confirmedAtMs = now,', semantic)
        self.assertNotIn('confirmedAtMs = current?.confirmedAtMs ?: now,', semantic)

    def test_explicit_reconfirmation_refreshes_old_source_receipt_version(self):
        self.observe('A',at=100)
        self.bind(at=120)
        self.authorize('A',baseline=100,now=130)
        self.assertEqual(self.proof('A'),1)
        # An explicit re-confirmation in the same root supersedes the old version.
        self.bind(at=145)
        self.assertEqual(self.proof('A'),0) # binding changed before recapture
        self.authorize('A',baseline=100,now=150)
        self.assertEqual(self.proof('A'),1)
        self.assertEqual(self.db.execute(
            'SELECT confirmedAtMs FROM artist_artwork_source_binding_proof').fetchone(),(145,))
        # Different source remains non-authoritative, even if albumKey matches.
        self.assertEqual(self.proof('B'),0)

    def test_old_binding_must_not_be_automatically_adopted_by_new_source(self):
        self.bind(at=20);self.observe('A',at=100)
        self.authorize('A',baseline=100,now=130)
        self.assertEqual(self.proof('A'),0)
        self.event('A')
        self.assertEqual(self.dispatch('A'),[])
        self.assertEqual(self.exec('holdUnprovenEventsForRoot',libraryId='lib',sourceId='A',nowMs=250).rowcount,1)
        self.assertEqual(self.db.execute("SELECT state FROM artist_artwork_auto_outbox").fetchone(),('NEEDS_SOURCE_PROOF',))

    def test_missing_catalog_pair_prevents_receipt_even_with_recent_binding(self):
        self.observe('A',album='another');self.bind()
        self.authorize('A')
        self.assertEqual(self.proof('A'),0)

    def test_unrelated_library_association_or_credit_never_authorizes(self):
        self.observe('A');self.bind();self.authorize('A')
        for kwargs in [dict(library='other'),dict(assoc='other'),dict(credit='other'),dict(album='other'),dict(source='B')]:
            self.assertEqual(self.proof(**kwargs),0)

    def test_invalidated_binding_timestamp_revokes_preexisting_proof(self):
        self.observe();self.bind(at=120);self.authorize()
        self.assertEqual(self.proof(),1)
        self.bind(at=400)
        self.assertEqual(self.proof(),0)
        self.event(at=200)
        self.assertEqual(self.dispatch(now=999),[])
        self.assertEqual(self.exec('holdUnprovenEventsForRoot',libraryId='lib',sourceId='A',nowMs=999).rowcount,1)

    def test_missing_proof_is_not_reopened_without_new_explicit_confirmation(self):
        self.bind(at=20);self.observe('A',at=100);self.event('A',state='NEEDS_SOURCE_PROOF')
        self.assertEqual(self.exec('reviveProvenSourceEvents',libraryId='lib',sourceId='A',
            creditKey='artist',associationId='assoc',nowMs=140).rowcount,0)
        self.bind(at=130);self.authorize('A',baseline=100,now=140)
        self.assertEqual(self.exec('reviveProvenSourceEvents',libraryId='lib',sourceId='A',
            creditKey='artist',associationId='assoc',nowMs=140).rowcount,1)
        self.assertEqual(len(self.dispatch(now=200)),1)

    def test_1300_unproven_entries_do_not_hide_valid_pending_artist(self):
        for i in range(1300):
            album=f'album-{i:04}'
            self.observe(album=album)
            self.bind(album=album,credit=f'old-{i}',assoc=f'old-{i}')
            self.event(album=album,credit=f'old-{i}',assoc=f'old-{i}',at=1)
        self.observe(album='winning',credit='winner');self.bind(album='winning',credit='winner',assoc='winner')
        self.authorize(credit='winner',assoc='winner')
        self.event(album='winning',credit='winner',assoc='winner',at=2)
        self.assertEqual([x[1] for x in self.dispatch(limit=128)],['winner'])
        self.assertEqual(self.exec('provenNextDeferredDueAtForRoot',libraryId='lib',sourceId='A',nowMs=0).fetchone(),(2,))
        self.assertEqual([x[1] for x in self.exec('provenManualRetryCandidates',libraryId='lib',sourceId='A',limit=16)],['winner'])
        self.assertEqual(self.exec('holdUnprovenEventsForRoot',libraryId='lib',sourceId='A',nowMs=999).rowcount,1300)

    def test_proven_worker_pending_and_explicit_backfill_have_same_scope(self):
        self.observe();self.bind();self.authorize();self.event('A',at=200)
        self.event('B',at=100)
        self.assertEqual(len(self.exec('provenPendingForRoot',libraryId='lib',sourceId='A',
            creditKey='artist',associationId='assoc',nowMs=250).fetchall()),1)
        self.assertEqual(len(self.exec('provenPendingForRoot',libraryId='lib',sourceId='B',
            creditKey='artist',associationId='assoc',nowMs=250).fetchall()),0)
        self.assertEqual(len(self.exec('provenBindingsAfter',libraryId='lib',sourceId='A',
            afterAlbum='',afterCredit='',limit=64).fetchall()),1)
        self.assertEqual(len(self.exec('provenBindingsAfter',libraryId='lib',sourceId='B',
            afterAlbum='',afterCredit='',limit=64).fetchall()),0)

    def test_ready_cooldown_and_other_root_survive_unproven_cleanup(self):
        self.bind();self.observe();self.authorize()
        self.event('A',album='other',state='READY')
        self.event('B',album='other',state='PENDING')
        self.event('A',album='album',state='PENDING',at=900)
        self.assertEqual(self.exec('holdUnprovenEventsForRoot',libraryId='lib',sourceId='A',nowMs=500).rowcount,0)
        self.assertEqual(self.db.execute("SELECT state FROM artist_artwork_auto_outbox WHERE sourceId='B'").fetchone(),('PENDING',))
        self.assertEqual(self.db.execute("SELECT state FROM artist_artwork_auto_outbox WHERE albumKey='album'").fetchone(),('PENDING',))

if __name__=='__main__':unittest.main()
