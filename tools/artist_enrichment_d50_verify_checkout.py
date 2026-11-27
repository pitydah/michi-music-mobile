#!/usr/bin/env python3
"""Read-only Git gate for ARTFIX2-D50.

Checks GitHub's attested 32 preimage blobs, exact immutable source commit,
checks all target names, builds an isolated Git worktree, then applies and
reverts the candidate diff. Never modifies the source checkout. This does not
compile Android or certify Room/Compose/SAF behavior.

Example:
 python3 artfix2_d50_verify_checkout.py \
     --checkout /path/to/michi-music-mobile \
     --patch /path/to/artenrich_fix2.patch \
     --manifest /path/to/github_blob_manifest.tsv \
     --expected-head b0104b932d0f04a7d5974bb22162919ae145e2b2
"""
from __future__ import annotations
import argparse, hashlib, pathlib, re, subprocess, sys, tempfile

FILLER = '__BASELINE_FILLER_'
class GateError(Exception): pass


def run(*args, cwd, timeout=90):
    try:
        p=subprocess.run(args,cwd=cwd,stdout=subprocess.PIPE,stderr=subprocess.PIPE,
                         text=True,encoding='utf-8',timeout=timeout)
    except (OSError,subprocess.TimeoutExpired) as exc:
        raise GateError(f'cannot execute {args[0]}: {exc}') from exc
    if p.returncode:
        raise GateError(f'{" ".join(args)} FAILED:\n{p.stderr[:1600]}')
    return p.stdout.strip()


def parse_patch(blob: str, strict=False):
    sections=re.split(r'(?=^diff --git )', blob, flags=re.M)
    existing=[];added=[]
    for sec in sections:
        if not sec: continue
        head=sec.splitlines()[0]
        m=re.fullmatch(r'diff --git a/([^\s]+) b/([^\s]+)',head)
        if not m or m.group(1)!=m.group(2):
            raise GateError(f'unsafe diff pathname/header: {head[:120]}')
        path=m.group(1)
        parts=pathlib.PurePosixPath(path).parts
        if path.startswith('/') or '..' in parts or path.endswith('/'):
            raise GateError(f'unsafe path: {path}')
        if strict and (not path.startswith(('app/', 'data/', 'tools/')) or
                       not re.fullmatch(r'[A-Za-z0-9_./-]+', path)):
            raise GateError(f'out-of-scope path: {path}')
        header=sec.split('\n@@ ',1)[0].splitlines()
        allowed_modes={'new file mode 100644'}
        if path == 'tools/artist_enrichment_d48_canonicalize.py':
            allowed_modes.add('new file mode 100755')
        is_new=any(l in allowed_modes for l in header)
        if any(l.startswith(('old mode ', 'new mode ', 'deleted file mode ', 'rename ', 'copy ',
                             'GIT binary patch', 'Binary files ')) for l in header):
            raise GateError(f'unsupported file operation: {path}')
        if any(l.startswith('new file mode ') and l not in allowed_modes for l in header):
            raise GateError(f'unsupported new file mode: {path}')
        expected_old='--- /dev/null' if is_new else '--- a/'+path
        if header.count(expected_old)!=1 or header.count('+++ b/'+path)!=1 or \
                sum(l.startswith(('--- ', '+++ ')) for l in header)!=2:
            raise GateError(f'diff path mismatch: {path}')
        if is_new: added.append(path)
        else: existing.append(path)
        if any(FILLER in l for l in sec.splitlines() if l.startswith((' ', '-'))):
            raise GateError(f'synthetic context in {path}')
    paths=existing+added
    if len(set(paths))!=len(paths):
        raise GateError('duplicate file paths in patch')
    if not paths:raise GateError('empty patch')
    return existing, added


def manifest_read(path):
    lines=path.read_text(encoding='utf-8').splitlines()
    if not lines or lines[0]!='path\tsha1':raise GateError('invalid manifest header')
    out={}
    for l in lines[1:]:
        fields=l.split('\t')
        if len(fields)!=2:raise GateError('malformed manifest line')
        k,sha=fields
        if k in out or not re.fullmatch('[0-9a-f]{40}',sha):
            raise GateError('duplicate/invalid blob sha')
        out[k]=sha
    return out


def check(checkout,patch,manifest,expected_head,strict_counts,required_remote=None,expected_sha256=None):
    if not checkout.is_dir() or not patch.is_file() or not manifest.is_file():
        raise GateError('checkout, patch or manifest missing')
    # D51: NEVER operate on another branch. A detached HEAD is not master.
    branch=run('git','symbolic-ref','--quiet','--short','HEAD',cwd=checkout)
    if branch != 'master':
        raise GateError(f'master required; current branch is {branch!r}')
    root=pathlib.Path(run('git','rev-parse','--show-toplevel',cwd=checkout)).resolve()
    if root != checkout.resolve():
        raise GateError(f'checkout is not the Git root: {checkout}')
    if required_remote:
        origin=run('git','remote','get-url','origin',cwd=checkout)
        # Support normal GitHub HTTPS, git@ and SSH clone URLs, but never a
        # similarly named fork, local bare path or unrelated desktop repo.
        accepted={
            'https://github.com/'+required_remote,
            'https://github.com/'+required_remote+'.git',
            'git@github.com:'+required_remote,
            'git@github.com:'+required_remote+'.git',
            'ssh://git@github.com/'+required_remote,
            'ssh://git@github.com/'+required_remote+'.git',
        }
        if origin not in accepted:
            raise GateError(f'origin mismatch: expected GitHub {required_remote}, received {origin!r}')
    head=run('git','rev-parse','HEAD',cwd=checkout)
    if expected_head and head!=expected_head:
        raise GateError(f'HEAD mismatch: {head} != {expected_head}; rebase first')
    if run('git','status','--porcelain',cwd=checkout):
        raise GateError('checkout has uncommitted/untracked changes')
    raw=patch.read_bytes()
    digest=hashlib.sha256(raw).hexdigest()
    if expected_sha256 is not None and digest != expected_sha256:
        raise GateError('patch SHA-256 mismatch')
    contents=raw.decode('utf-8')
    existing,added=parse_patch(contents,strict=strict_counts)
    entries=manifest_read(manifest)
    if set(existing)!=set(entries):
        raise GateError(f'blob manifest mismatch: missing={set(existing)-set(entries)} extra={set(entries)-set(existing)}')
    if strict_counts and (len(existing)!=32 or len(added)!=134):
        raise GateError(f'unexpected route counts existing={len(existing)}, added={len(added)}')
    for path in existing:
        sha=run('git','rev-parse',f'HEAD:{path}',cwd=checkout)
        if sha!=entries[path]:
            raise GateError(f'original Git blob mismatch for {path}: {sha} != {entries[path]}')
    for path in added:
        if subprocess.run(['git','-C',str(checkout),'cat-file','-e',f'HEAD:{path}'],capture_output=True).returncode==0:
            raise GateError(f'added target already exists at HEAD: {path}')
    # Never apply anything to the user's checkout. Isolated worktree detached at verified HEAD.
    with tempfile.TemporaryDirectory(prefix='artfix2-d50-gate-') as work:
        isolated=pathlib.Path(work)/'repo'
        frozen=pathlib.Path(work)/'verified.patch'
        frozen.write_bytes(raw)
        run('git','worktree','add','--detach','--quiet',str(isolated),'HEAD',cwd=checkout)
        try:
            run('git','apply','--check','--whitespace=error',str(frozen),cwd=isolated)
            run('git','apply','--whitespace=error',str(frozen),cwd=isolated)
            run('git','apply','--reverse','--check',str(frozen),cwd=isolated)
            run('git','apply','--reverse',str(frozen),cwd=isolated)
            if run('git','status','--porcelain',cwd=isolated):
                raise GateError('revert did not restore a clean worktree')
        finally:
            run('git','worktree','remove','--force',str(isolated),cwd=checkout)
    if hashlib.sha256(patch.read_bytes()).hexdigest()!=digest:
        raise GateError('patch changed during verification')
    if run('git','rev-parse','HEAD',cwd=checkout)!=head or run('git','status','--porcelain',cwd=checkout):
        raise GateError('source checkout changed during verification')
    return {'head':head,'existing':len(existing),'added':len(added),
            'total':len(existing)+len(added),
            'patch_sha256':digest}


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--checkout',type=pathlib.Path,required=True)
    p.add_argument('--patch',type=pathlib.Path,required=True)
    p.add_argument('--manifest',type=pathlib.Path,required=True)
    p.add_argument('--expected-head',default='b0104b932d0f04a7d5974bb22162919ae145e2b2')
    p.add_argument('--expected-repository',default='pitydah/michi-music-mobile',choices=['pitydah/michi-music-mobile'])
    p.add_argument('--expected-sha256',required=True)
    p.add_argument('--test-allow-small-fixture',action='store_true',help=argparse.SUPPRESS)
    a=p.parse_args()
    try:
        proof=check(a.checkout.resolve(),a.patch.resolve(),a.manifest.resolve(),
                    a.expected_head,not a.test_allow_small_fixture,
                    required_remote=None if a.test_allow_small_fixture else a.expected_repository,
                    expected_sha256=a.expected_sha256)
        print('ARTFIX2-D57 MASTER GIT APPLY + REVERSE PASS',proof)
        print('ANDROID/ROOM/COMPOSE/WORKMANAGER: NOT EXECUTED')
    except (GateError,ValueError,UnicodeError) as exc:
        print('ARTFIX2-D56 MASTER NO-GO:',str(exc),file=sys.stderr)
        return 1
    return 0
if __name__=='__main__':sys.exit(main())
