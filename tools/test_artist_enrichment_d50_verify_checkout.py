#!/usr/bin/env python3
"""D50 git gate regression suite. No Android SDK or internet required."""
from pathlib import Path
import importlib.util
import subprocess
import tempfile
import unittest

spec=importlib.util.spec_from_file_location('d50_gate',Path(__file__).with_name('artist_enrichment_d50_verify_checkout.py'))
mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod)


def cmd(repo, *args):
    return subprocess.check_output(['git',*args],cwd=repo,text=True,stderr=subprocess.STDOUT).strip()

class GateTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)
        self.repo=self.root/'repo';self.repo.mkdir()
        cmd(self.repo,'-c','init.defaultBranch=master','init','-q');cmd(self.repo,'config','user.email','test@local')
        cmd(self.repo,'config','user.name','test')
        (self.repo/'src.kt').write_text('package example\n\nfun text() = 1\n')
        cmd(self.repo,'add','.');cmd(self.repo,'commit','-qm','base')
        self.head=cmd(self.repo,'rev-parse','HEAD')
        self.sha=cmd(self.repo,'rev-parse','HEAD:src.kt')
        self.manifest=self.root/'manifest.tsv'
        self.manifest.write_text('path\tsha1\nsrc.kt\t'+self.sha+'\n')
        (self.repo/'src.kt').write_text('package example\n\nfun text() = 2\n')
        self.patch=self.root/'change.patch'
        self.patch.write_text(cmd(self.repo,'diff','--no-ext-diff')+'\n')
        cmd(self.repo,'checkout','--','src.kt')

    def verify(self):
        return mod.check(self.repo,self.patch,self.manifest,self.head,False)

    def test_pristine_immutable_checkout_passes(self):
        self.assertEqual(1,self.verify()['existing'])
        self.assertEqual('',cmd(self.repo,'status','--porcelain'))

    def test_other_branch_rejected_even_at_same_head(self):
        cmd(self.repo,'checkout','-qb','integration-temp')
        with self.assertRaisesRegex(mod.GateError,'master required'):
            self.verify()

    def test_unexpected_origin_rejected(self):
        cmd(self.repo,'remote','add','origin','https://github.com/not-the-project/other.git')
        with self.assertRaisesRegex(mod.GateError,'origin mismatch'):
            mod.check(self.repo,self.patch,self.manifest,self.head,False,
                      required_remote='pitydah/michi-music-mobile')

    def test_authentic_origin_accepted_without_network(self):
        cmd(self.repo,'remote','add','origin','git@github.com:pitydah/michi-music-mobile.git')
        proof=mod.check(self.repo,self.patch,self.manifest,self.head,False,
                        required_remote='pitydah/michi-music-mobile')
        self.assertEqual(self.head,proof['head'])

    def test_changed_head_fails(self):
        with self.assertRaisesRegex(mod.GateError,'HEAD mismatch'):
            mod.check(self.repo,self.patch,self.manifest,'0'*40,False)

    def test_dirty_worktree_fails_before_patch(self):
        (self.repo/'src.kt').write_text('dirty\n')
        with self.assertRaisesRegex(mod.GateError,'uncommitted'):
            self.verify()

    def test_mismatched_blob_fails(self):
        self.manifest.write_text('path\tsha1\nsrc.kt\t'+'a'*40+'\n')
        with self.assertRaisesRegex(mod.GateError,'Git blob mismatch'):
            self.verify()

    def test_synthetic_context_fails(self):
        self.patch.write_text(self.patch.read_text().replace('-fun text() = 1',' __BASELINE_FILLER_'))
        with self.assertRaisesRegex(mod.GateError,'synthetic context'):
            self.verify()

    def test_duplicate_path_fails(self):
        self.patch.write_text(self.patch.read_text()*2)
        with self.assertRaisesRegex(mod.GateError,'duplicate'):
            self.verify()

    def test_failed_apply_does_not_dirty_checkout(self):
        self.patch.write_text(self.patch.read_text().replace('fun text() = 1','fun text() = 999'))
        with self.assertRaisesRegex(mod.GateError,'FAILED'):
            self.verify()
        self.assertEqual('',cmd(self.repo,'status','--porcelain'))

    def test_wrong_patch_digest_rejected(self):
        with self.assertRaisesRegex(mod.GateError,'SHA-256 mismatch'):
            mod.check(self.repo,self.patch,self.manifest,self.head,False,expected_sha256='0'*64)

    def test_header_redirect_rejected(self):
        self.patch.write_text(self.patch.read_text().replace('+++ b/src.kt','+++ b/other.kt'))
        with self.assertRaisesRegex(mod.GateError,'path mismatch'):
            self.verify()

    def test_mode_change_rejected(self):
        self.patch.write_text(self.patch.read_text().replace('--- a/src.kt','old mode 100644\nnew mode 120000\n--- a/src.kt'))
        with self.assertRaisesRegex(mod.GateError,'unsupported file operation'):
            self.verify()

if __name__=='__main__':unittest.main()
