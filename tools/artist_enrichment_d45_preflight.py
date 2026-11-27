#!/usr/bin/env python3
"""ARTFIX2 D45: refuse synthetic success. Requires a *real* checkout.

Usage: python3 tools/artist_enrichment_d45_preflight.py /path/to/artenrich_fix2.patch
Always run before OpenCode edits; no writes performed by this script.
"""
import pathlib
import subprocess
import sys


def check(patch_path: pathlib.Path, repository: pathlib.Path) -> int:
    if not (repository / '.git').exists() and not (repository / '.git').is_file():
        print('NO-GO: directory is not a Git checkout')
        return 2
    if not patch_path.is_file() or patch_path.stat().st_size < 1000:
        print('NO-GO: patch missing or unexpectedly small')
        return 2
    paths = [line.split(' b/', 1)[1] for line in patch_path.read_text().splitlines()
             if line.startswith('diff' + ' --git a/') and ' b/' in line]
    if len(paths) != len(set(paths)) or len(paths) < 149:
        print('NO-GO: duplicate or missing cumulative paths')
        return 2
    head = subprocess.run(['git', 'rev-parse', 'HEAD'], cwd=repository,
                          text=True, capture_output=True)
    if head.returncode:
        print('NO-GO: Git checkout not readable')
        return 2
    result = subprocess.run(['git', 'apply', '--check', '--whitespace=error',
                             str(patch_path.resolve())], cwd=repository,
                            text=True, capture_output=True)
    print(f'HEAD={head.stdout.strip()} paths={len(paths)}')
    if result.returncode:
        # Script lives in the cumulative patch: it will usually be available only
        # AFTER applying the patch. In that case demand a clean reverse check.
        reverse = subprocess.run(['git', 'apply', '--reverse', '--check',
                                  str(patch_path.resolve())], cwd=repository,
                                 text=True, capture_output=True)
        if reverse.returncode:
            print('NO-GO: neither forward nor reverse apply is valid in this real checkout')
            print((result.stderr or result.stdout)[:5000])
            return 1
        print('POST-APPLY PASS: real checkout git apply --reverse --check')
    else:
        print('PRE-APPLY PASS: real checkout git apply --check --whitespace=error')
    print('UNVERIFIED: Android compilation, Room migration, Compose rendering, source-file permissions')
    return 0


if __name__ == '__main__':
    if len(sys.argv) not in (2, 3):
        print('usage: artist_enrichment_d45_preflight.py PATCH [REPO]')
        raise SystemExit(2)
    raise SystemExit(check(pathlib.Path(sys.argv[1]),
                           pathlib.Path(sys.argv[2]) if len(sys.argv) == 3 else pathlib.Path.cwd()))
