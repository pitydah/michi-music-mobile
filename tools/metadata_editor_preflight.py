#!/usr/bin/env python3
"""ME-11R: read-only acceptance preflight for the ONE accumulated patch.

This script does not apply the patch, compile Android, modify user files,
change safety gates, or authorize writes. Run from a real Android checkout in
OpenCode; supply --repo to check current HEAD compatibility.
"""
import argparse
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

BLOCK = re.compile(r'^diff --git a/(\S+) b/(\S+)$', re.M)
TEST = re.compile(r'^\+\s*@(?:org\.junit\.)?Test\b', re.M)
GATES = {
    'MetadataCommitCertification.WRITE_CAPABILITIES_CERTIFIED':
        r'const val WRITE_CAPABILITIES_CERTIFIED(?:\s*:\s*Boolean)?\s*=\s*false',
    'MetadataSafRenameCertification.PHYSICAL_RENAME_CERTIFIED':
        r'const val PHYSICAL_RENAME_CERTIFIED\s*=\s*false',
    'MetadataSafRenameRoomPublicationCertification.URI_CAS_CERTIFIED':
        r'const val URI_CAS_CERTIFIED\s*=\s*false',
}


def audit(patch: Path, repo: Path | None = None) -> dict:
    raw = patch.read_bytes()
    diff = raw.decode('utf-8')
    pairs = BLOCK.findall(diff)
    paths = [b for a, b in pairs]
    errors = []
    if not pairs or len(paths) != len(set(paths)):
        errors.append('NO_PATHS_OR_DUPLICATE_PATHS')
    if any(a != b for a, b in pairs):
        errors.append('RENAMES_OR_MISMATCHED_PATHS')
    if any(name.startswith('../') or '/..' in name or name.startswith('/')
           or name.startswith('.git/') for name in paths):
        errors.append('UNSAFE_PATH')
    forbidden = [name for name in paths if '/media3/' in name.lower()
                 or 'michi-link' in name.lower() or 'aircat' in name.lower()]
    if forbidden:
        errors.append('OUTSIDE_EDITOR_SCOPE')
    # A single unified diff must be directly parseable by Git.
    numstat = subprocess.run(['git', 'apply', '--numstat', '--'], input=raw,
                             stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if numstat.returncode != 0:
        errors.append('INVALID_UNIFIED_DIFF')
    missing_gates = [name for name, pattern in GATES.items()
                     if re.search(pattern, diff) is None]
    if missing_gates:
        errors.append('MISSING_FAIL_CLOSED_GATES')
    dangerous_true = bool(re.search(
        r'(?m)^\+\s*(?:const\s+val|val)\s*'
        r'(?:WRITE_CAPABILITIES_CERTIFIED|WRITES_CERTIFIED|'
        r'PHYSICAL_RENAME_CERTIFIED|URI_CAS_CERTIFIED)\s*=\s*true\b', diff))
    if dangerous_true:
        errors.append('SOURCE_MUTATION_GATE_ENABLED')
    repo_check = 'NOT_REQUESTED'
    repo_head = None
    if repo is not None:
        if not (repo / '.git').exists():
            errors.append('NOT_A_GIT_CHECKOUT')
            repo_check = 'INVALID_REPOSITORY'
        else:
            head = subprocess.run(['git', '-C', str(repo), 'rev-parse', 'HEAD'],
                                  stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            repo_head = head.stdout.strip() if head.returncode == 0 else None
            attempt = subprocess.run(['git', '-C', str(repo), 'apply', '--check', '--'],
                                     input=raw, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            repo_check = 'PASS' if attempt.returncode == 0 else 'FAIL_RECONCILE_BEFORE_APPLY'
            if attempt.returncode != 0:
                errors.append('PATCH_NOT_APPLICABLE_TO_CURRENT_HEAD')
    return {
        'status': 'PASS_STATIC' if not errors else 'FAIL',
        'sha256': hashlib.sha256(raw).hexdigest(),
        'size_bytes': len(raw),
        'paths': len(paths),
        'new_source_paths': sum(x.startswith(('app/', 'data/', 'core/')) for x in paths),
        'test_methods_declared_in_added_lines': len(TEST.findall(diff)),
        'duplicate_paths': len(paths) - len(set(paths)),
        'git_apply_numstat': 'PASS' if numstat.returncode == 0 else 'FAIL',
        'gate_false_markers': sorted(set(GATES) - set(missing_gates)),
        'baseline_gates_require_checkout_check': ['MichiGenreWriteCertification.WRITES_CERTIFIED'],
        'repository_head': repo_head,
        'git_apply_check_on_head': repo_check,
        'errors': errors,
        'not_certified_by_this_script': [
            'Android Gradle/Room/KSP compilation', 'JUnit and instrumented tests',
            'SAF or MediaStore provider guarantees', 'physical media mutation',
            'metadata write recovery', 'UI, battery, accessibility and CI',
        ],
    }


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('patch', type=Path, help='Original accumulated .patch')
    ap.add_argument('--repo', type=Path, help='Android checkout for non-mutating git apply --check')
    args = ap.parse_args()
    try:
        report = audit(args.patch, args.repo)
    except (OSError, ValueError, UnicodeError) as exc:
        print(json.dumps({'status': 'FAIL', 'errors': [str(exc)]}, ensure_ascii=False))
        return 2
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report['status'] == 'PASS_STATIC' else 2


if __name__ == '__main__':
    sys.exit(main())
