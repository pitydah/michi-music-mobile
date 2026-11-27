#!/usr/bin/env python3
import importlib.util
import pathlib
import subprocess
import tempfile
import unittest

TARGET = pathlib.Path(__file__).with_name('artist_enrichment_d48_canonicalize.py')
spec = importlib.util.spec_from_file_location('canonicalize_d48', TARGET)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def cmd(cwd, *args):
    subprocess.run(args, cwd=cwd, check=True, capture_output=True)


class CanonicalPatchTest(unittest.TestCase):
    def test_replace_preserves_unknown_real_content(self):
        old = 'header\n__BASELINE_FILLER_2__\nfun work() {\n    old()\n}\n__BASELINE_FILLER_7__\n'
        new = old.replace('    old()', '    fixed()')
        real = 'header\nproprietary untouched code\nfun work() {\n    old()\n}\nunchanged source tail\n'
        self.assertEqual(module.transfer(old, new, real), real.replace('    old()', '    fixed()'))

    def test_insertion_next_to_authentic_anchor(self):
        old = 'start\n__BASELINE_FILLER_2__\nfun work() {\n}\n'
        new = old.replace('fun work() {\n', 'fun work() {\n    added()\n')
        real = 'start\nreal missing lines\nfun work() {\n}\n'
        self.assertEqual(module.transfer(old, new, real), real.replace('fun work() {\n', 'fun work() {\n    added()\n'))

    def test_non_unique_anchor_rejected(self):
        with self.assertRaises(module.CannotRebase):
            module.transfer('same\n', 'changed\n', 'same\nsame\n')

    def test_changing_filler_is_refused(self):
        with self.assertRaises(module.CannotRebase):
            module.transfer('__BASELINE_FILLER_1__\n', 'wrong code\n', 'real\n')

    def test_stale_github_blob_refused_before_rebase(self):
        with tempfile.TemporaryDirectory() as temp:
            base = pathlib.Path(temp)
            author = base/'author'
            real = base/'real'
            author.mkdir(); real.mkdir()
            for repo in (author, real):
                cmd(repo, 'git', 'init', '-q')
                cmd(repo, 'git', 'config', 'user.email', 'test@example.com')
                cmd(repo, 'git', 'config', 'user.name', 'Test')
            (author/'old.kt').write_text('same\n__BASELINE_FILLER_2__\n')
            (real/'old.kt').write_text('same\nactual original\n')
            for repo in (author, real):
                cmd(repo, 'git', 'add', '.')
                cmd(repo, 'git', 'commit', '-qm', 'base')
            (author/'old.kt').write_text('different\n__BASELINE_FILLER_2__\n')
            manifest = base/'blobs.tsv'
            manifest.write_text('path\tsha1\nold.kt\t'+'a'*40+'\n')
            output = base/'out.patch'
            with self.assertRaisesRegex(module.CannotRebase, 'blob mismatch'):
                module.canonicalize(author, real, output, manifest)
            self.assertFalse(output.exists())

    def test_manifest_coverage_must_match_exactly(self):
        with tempfile.TemporaryDirectory() as temp:
            base=pathlib.Path(temp)
            author=base/'author'; real=base/'real'
            author.mkdir(); real.mkdir()
            for repo in (author, real):
                cmd(repo,'git','init','-q')
                cmd(repo,'git','config','user.email','test@example.com')
                cmd(repo,'git','config','user.name','Test')
                (repo/'same.kt').write_text('unchanged\n')
                cmd(repo,'git','add','.')
                cmd(repo,'git','commit','-qm','base')
            (author/'same.kt').write_text('modified\n')
            manifest=base/'bad.tsv'
            manifest.write_text('path\tsha1\nother.kt\t'+'a'*40+'\n')
            with self.assertRaisesRegex(module.CannotRebase, 'coverage mismatch'):
                module.canonicalize(author,real,base/'out.patch',manifest)

    def test_actual_git_checkout_with_new_source(self):
        with tempfile.TemporaryDirectory() as temp:
            base = pathlib.Path(temp)
            author = base/'author'
            real = base/'real'
            author.mkdir(); real.mkdir()
            for repo in (author, real):
                cmd(repo, 'git', 'init', '-q')
                cmd(repo, 'git', 'config', 'user.email', 'test@example.com')
                cmd(repo, 'git', 'config', 'user.name', 'Test')
            original = 'start\n__BASELINE_FILLER_2__\nfun a() {\n  before()\n}\n'
            actual = 'start\nuntouched true code\nfun a() {\n  before()\n}\n'
            (author/'old.kt').write_text(original)
            (real/'old.kt').write_text(actual)
            for repo in (author, real):
                cmd(repo, 'git', 'add', '.'); cmd(repo, 'git', 'commit', '-qm', 'base')
            (author/'old.kt').write_text(original.replace('  before()', '  after()'))
            (author/'new.kt').write_text('new source\n')
            cmd(author, 'git', 'add', '-N', 'new.kt')
            manifest = base/'blobs.tsv'
            sha = subprocess.check_output(['git', 'rev-parse', 'HEAD:old.kt'], cwd=real,
                                          text=True).strip()
            manifest.write_text('path\tsha1\nold.kt\t'+sha+'\n')
            output = base/'canonical.patch'
            module.canonicalize(author, real, output, manifest)
            data = output.read_text()
            self.assertIn('after()', data)
            self.assertIn('new.kt', data)
            self.assertNotIn('__BASELINE_FILLER_', data)
            self.assertNotIn('untouched true code\n-', data)
            self.assertEqual(subprocess.run(['git', 'apply', '--check', str(output)], cwd=real).returncode, 0)

if __name__ == '__main__':
    unittest.main()
