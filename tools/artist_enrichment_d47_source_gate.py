#!/usr/bin/env python3
"""D47 static source contract. Run only AFTER applying the cumulative patch.

This checks a few structural regressions, NOT Kotlin compilation or Android behaviour.
Usage: python3 tools/artist_enrichment_d47_source_gate.py [REPO_ROOT]
"""
from pathlib import Path
import sys

root = Path(sys.argv[1] if len(sys.argv) == 2 else '.').resolve()
source = root / 'app/src/main/java/org/michimusic/mobile/library/ArtistExplicitTrackChoiceStore.kt'
qfile = root / 'app/src/main/java/org/michimusic/mobile/artist/artwork/ArtistArtworkQuarantine.kt'
pairfile = root / 'app/src/main/java/org/michimusic/mobile/library/ArtistTrackProofPair.kt'
if any(not p.is_file() for p in (source, qfile, pairfile)):
    raise SystemExit('NO-GO: D47 source files missing')
s = source.read_text()
q = qfile.read_text()
p = pairfile.read_text()
transaction = s.split('database.withTransaction {', 1)
if len(transaction) != 2:
    raise SystemExit('NO-GO: transaction boundary missing')
body = transaction[1].split('} catch (_: Refused)', 1)[0]
assert body != transaction[1], 'Transaction body not bounded by expected error handler'
checks = {
    'two reads in one source admission gate': 'ArtistTrackProofPair.read(claim, currentSourceProof)' in s,
    'source validation before transaction': s.index('ArtistTrackProofPair.read(') < s.index('database.withTransaction {'),
    'no SHA reader within transaction': 'currentSourceProof(' not in body and 'ArtistTrackProofPair.read(' not in body,
    'two proof reads and match': p.count('source(claim)') == 2 and 'second == first' in p,
    'quarantine isolation still atomic': 'Files.move(path, quarantine, StandardCopyOption.ATOMIC_MOVE)' in q,
    'restoration fails if target exists': 'Files.createLink(source, quarantine)' in q,
    'unexpected inode is reviewed': 'restoreUnexpected(quarantine, path)' in q and 'ReviewRequired' in q,
}
for name, value in checks.items():
    print(('PASS' if value else 'NO-GO'), name)
if not all(checks.values()):
    raise SystemExit(1)
print('7/7 static gates PASS; NOT a Gradle/Room/Compose/SAF certification')
