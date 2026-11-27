#!/usr/bin/env python3
"""D48 KILLCRITIC: convert a cumulative *synthetic* authoring patch into a
real, fail-closed patch against a user's CLEAN Git checkout.

Never modifies the user's checkout. Never guesses a missing anchor. Fails if a
synthetic filler would be inserted into a production source. No network use.

Usage:
  python3 tools/artist_enrichment_d48_canonicalize.py \
      --authoring-tree /path/ARTFIX2-D48/repo \
      --checkout /path/michi-music-mobile \
      --github-manifest /path/ARTFIX2-D48/repo/tools/artist_enrichment_d49_github_blobs.tsv \
      --output /path/artenrich_fix2_canonical.patch

The authoring tree must have a synthetic baseline committed in HEAD and pending
D8-D49 changes. The GitHub manifest captures actual blobs of ALL 32 existing files
modified by the patch, and the real checkout must match them before any transfer.
This does NOT certify Kotlin/Room/Compose compilation.
"""
from __future__ import annotations

import argparse
import difflib
import pathlib
import subprocess
import sys
import tempfile

FILLER = '__BASELINE_FILLER_'


class CannotRebase(Exception):
    pass


def git(repo: pathlib.Path, *args: str) -> str:
    p = subprocess.run(['git', '-C', str(repo), *args], capture_output=True,
                       text=True, encoding='utf-8')
    if p.returncode:
        raise CannotRebase(f"git {' '.join(args)}: {p.stderr.strip()[:1000]}")
    return p.stdout


def unique_position(lines: list[str], pattern: list[str],
                    before: list[str] | None = None,
                    after: list[str] | None = None) -> int:
    """Return only uniquely matching span. No first-match or fuzzy guessing."""
    if not pattern:
        raise CannotRebase('empty anchor')
    indexes = [i for i in range(len(lines)-len(pattern)+1)
               if lines[i:i+len(pattern)] == pattern]
    if len(indexes) == 1:
        return indexes[0]
    if len(indexes) > 1 and (before or after):
        # Resolve duplicates only using exact context on either side of the
        # edited source. This is not line-number guessing or fuzzy replacement.
        matches = []
        for pos in indexes:
            left = before or []
            right = after or []
            left_ok = bool(left) and pos >= len(left) and lines[pos-len(left):pos] == left
            right_ok = bool(right) and lines[pos+len(pattern):pos+len(pattern)+len(right)] == right
            if (left_ok and not right) or (right_ok and not left) or (left_ok and right_ok):
                matches.append(pos)
        if len(matches) == 1:
            return matches[0]
    raise CannotRebase(f'anchor not unique ({len(indexes)} matches): {pattern[:3]!r}')


def nearest_anchor(lines: list[str], at: int, before: bool, length: int = 4) -> list[str]:
    if before:
        values = []
        i = at-1
        while i >= 0 and len(values) < length and FILLER not in lines[i]:
            values.insert(0, lines[i]); i -= 1
        return values
    values = []
    i = at
    while i < len(lines) and len(values) < length and FILLER not in lines[i]:
        values.append(lines[i]); i += 1
    return values


def insertion_index(real: list[str], old: list[str], i: int) -> int:
    before = nearest_anchor(old, i, True)
    after = nearest_anchor(old, i, False)
    if before and after:
        # Match the literal bounding anchors with a unique interval. Gap may
        # contain lines absent from the synthetic source. Insert beside the
        # closest verified old anchor, never inside an unknown gap.
        try:
            left = unique_position(real, before) + len(before)
            right = unique_position(real, after)
            if left == right:
                return left
            # Synthetic filler means *unknown* intervening original lines: only
            # allow an insertion when a same-side anchor is adjacent in old.
            if i > 0 and FILLER not in old[i-1]:
                return left
            if i < len(old) and FILLER not in old[i]:
                return right
            raise CannotRebase('insertion cannot be located across unknown baseline lines')
        except CannotRebase:
            raise
    if before:
        return unique_position(real, before) + len(before)
    if after:
        return unique_position(real, after)
    raise CannotRebase('no authentic insertion anchor')


def transfer(old_text: str, new_text: str, real_text: str) -> str:
    """Apply only changes proven against unique, authentic old-source spans."""
    old = old_text.splitlines(keepends=True)
    new = new_text.splitlines(keepends=True)
    real = real_text.splitlines(keepends=True)
    if old == new:
        return real_text
    edits = []
    for op, i1, i2, j1, j2 in difflib.SequenceMatcher(
            a=old, b=new, autojunk=False).get_opcodes():
        if op == 'equal':
            continue
        old_piece, new_piece = old[i1:i2], new[j1:j2]
        if any(FILLER in x for x in old_piece + new_piece):
            raise CannotRebase('a change touches synthetic filler: manual reconciliation required')
        if old_piece:
            # Old code must occur *exactly once* in the real checkout. If the
            # old baseline changed, abort rather than papering over drift.
            pos = unique_position(real, old_piece,
                                  nearest_anchor(old, i1, True, 8),
                                  nearest_anchor(old, i2, False, 8))
        else:
            pos = insertion_index(real, old, i1)
        edits.append((pos, len(old_piece), new_piece))
    # Distinct authoring hunks must not rewrite overlapping real regions.
    edits.sort(key=lambda x: x[0])
    for (pos, n, _), (next_pos, _, _) in zip(edits, edits[1:]):
        if pos+n > next_pos or (pos == next_pos and n == 0):
            raise CannotRebase('ambiguous overlapping edits')
    for pos, n, replacement in reversed(edits):
        if n > 0 and real[pos:pos+n] == replacement:
            continue  # leave equivalent changes alone, but still report clean patch
        real[pos:pos+n] = replacement
    result = ''.join(real)
    if FILLER in result:
        raise CannotRebase('synthetic filler reached final source')
    return result


def verify_github_blob_manifest(
    checkout: pathlib.Path,
    authoring: pathlib.Path,
    paths: list[str],
    manifest: pathlib.Path,
) -> None:
    """Fail closed unless every touched existing file matches its recorded GitHub blob.

    This is a *per-file* content attestation, not an attestation of GitHub HEAD or
    any Android integration. It prevents fuzzy anchoring into a changed checkout.
    Every existing modified path is mandatory; newly added paths must not appear.
    """
    if not manifest.is_file():
        raise CannotRebase('trusted GitHub blob manifest is missing')
    entries: dict[str, str] = {}
    lines = manifest.read_text(encoding='utf-8').splitlines()
    if not lines or lines[0] != 'path\tsha1':
        raise CannotRebase('invalid GitHub blob manifest header')
    for line in lines[1:]:
        fields = line.split('\t')
        if len(fields) != 2:
            raise CannotRebase('malformed GitHub blob record')
        path, sha = fields
        if (path.startswith('/') or '..' in pathlib.PurePosixPath(path).parts or
            not path or path in entries or not all(c in '0123456789abcdef' for c in sha) or
            len(sha) != 40):
            raise CannotRebase('invalid or duplicate GitHub blob record: ' + path)
        entries[path] = sha
    existing = {
        path for path in paths if subprocess.run(
            ['git', '-C', str(authoring), 'cat-file', '-e', f'HEAD:{path}'],
            capture_output=True,
        ).returncode == 0
    }
    if set(entries) != existing:
        missing = sorted(existing - set(entries))
        unexpected = sorted(set(entries) - existing)
        raise CannotRebase('manifest coverage mismatch; missing=' + str(missing[:4]) +
                           ', unexpected=' + str(unexpected[:4]))
    for path in sorted(existing):
        observed = git(checkout, 'rev-parse', f'HEAD:{path}').strip()
        if observed != entries[path]:
            raise CannotRebase(f'GitHub blob mismatch for {path}: expected {entries[path]}, '
                               f'checkout has {observed}. Reaudit; never fuzzy-apply.')


def canonicalize(authoring: pathlib.Path, checkout: pathlib.Path, output: pathlib.Path,
                 github_manifest: pathlib.Path) -> None:
    if output.exists():
        raise CannotRebase('output already exists; refusing overwrite')
    if git(checkout, 'status', '--porcelain').strip():
        raise CannotRebase('real checkout must be completely clean')
    changed = git(authoring, 'status', '--porcelain').splitlines()
    if not changed:
        raise CannotRebase('authoring tree has no cumulative changes')
    paths = []
    for line in changed:
        status, path = line[:2], line[3:]
        if status[0] not in ' MA' or status[1] not in ' MA':
            raise CannotRebase(f'unsupported authoring status: {line}')
        if ' -> ' in path or path.startswith('/') or '..' in pathlib.PurePosixPath(path).parts:
            raise CannotRebase(f'unsafe/renamed path: {path}')
        paths.append(path)
    if len(set(paths)) != len(paths):
        raise CannotRebase('duplicate changed paths')
    baseline = git(checkout, 'rev-parse', 'HEAD').strip()
    # D49 critical gate: no write, no worktree, no code transfer occurs unless
    # every authentic existing-file blob agrees with the GitHub source snapshot.
    verify_github_blob_manifest(checkout, authoring, paths, github_manifest)
    with tempfile.TemporaryDirectory(prefix='artfix2-d48-') as temporary:
        isolated = pathlib.Path(temporary) / 'checkout'
        git(checkout, 'worktree', 'add', '--detach', '--quiet', str(isolated), 'HEAD')
        try:
            for path in paths:
                source = authoring / path
                dest = isolated / path
                if not source.is_file():
                    raise CannotRebase(f'missing authoring file {path}')
                new_text = source.read_text(encoding='utf-8')
                if FILLER in new_text and not (authoring / '.git').is_file():
                    # Synthetic working trees legitimately retain filler in
                    # UNMODIFIED regions; these are replaced by authentic base.
                    pass
                existing_in_authoring = subprocess.run(
                    ['git', '-C', str(authoring), 'cat-file', '-e', f'HEAD:{path}'],
                    capture_output=True).returncode == 0
                if existing_in_authoring:
                    if not dest.is_file():
                        raise CannotRebase(f'original source missing in real checkout: {path}')
                    old_text = git(authoring, 'show', f'HEAD:{path}')
                    dest.write_text(transfer(old_text, new_text,
                                             dest.read_text(encoding='utf-8')), encoding='utf-8')
                else:
                    if dest.exists():
                        raise CannotRebase(f'new file already exists in real checkout: {path}')
                    if FILLER in new_text:
                        raise CannotRebase(f'new file is synthetic: {path}')
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    dest.write_text(new_text, encoding='utf-8')
            # Include newly created files in git diff without staging their content.
            new_paths = [p for p in paths if subprocess.run(
                ['git', '-C', str(isolated), 'cat-file', '-e', f'HEAD:{p}'],
                capture_output=True).returncode != 0]
            if new_paths:
                git(isolated, 'add', '-N', '--', *new_paths)
            # Show *actual* HEAD-to-worktree diff, not a synthetic reconstruction.
            patch = git(isolated, 'diff', '--no-ext-diff', '--binary', 'HEAD')
            if not patch.strip() or FILLER in patch or '\x00' in patch:
                raise CannotRebase('empty, synthetic or binary-contaminated output')
            output.write_text(patch, encoding='utf-8')
            verify = subprocess.run(['git', '-C', str(isolated), 'apply', '--reverse',
                                     '--check', str(output)], capture_output=True, text=True)
            if verify.returncode:
                output.unlink(missing_ok=True)
                raise CannotRebase('canonical patch failed reverse check: ' + verify.stderr[:1500])
            # Forward check against authentic clean checkout, non-destructively.
            with tempfile.TemporaryDirectory(prefix='artfix2-clean-') as another:
                clean = pathlib.Path(another) / 'checkout'
                git(checkout, 'worktree', 'add', '--detach', '--quiet', str(clean), 'HEAD')
                try:
                    check = subprocess.run(['git', '-C', str(clean), 'apply', '--check',
                                            '--whitespace=error', str(output)],
                                           capture_output=True, text=True)
                    if check.returncode:
                        output.unlink(missing_ok=True)
                        raise CannotRebase('canonical patch failed real forward check: '+check.stderr[:1500])
                finally:
                    git(checkout, 'worktree', 'remove', '--force', str(clean))
            print(f'CANONICAL CHECK PASS HEAD={baseline} paths={len(paths)} github_blobs={len(paths)-len(new_paths)} output={output}')
            print('UNVERIFIED: Android build, Room migrations, Compose screens, device I/O')
        finally:
            git(checkout, 'worktree', 'remove', '--force', str(isolated))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--authoring-tree', required=True, type=pathlib.Path)
    parser.add_argument('--checkout', required=True, type=pathlib.Path)
    parser.add_argument('--output', required=True, type=pathlib.Path)
    parser.add_argument('--github-manifest', required=True, type=pathlib.Path)
    args = parser.parse_args()
    try:
        canonicalize(args.authoring_tree.resolve(), args.checkout.resolve(), args.output.resolve(),
                     args.github_manifest.resolve())
        return 0
    except (CannotRebase, ValueError, OSError) as exc:
        print('NO-GO: '+str(exc), file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
