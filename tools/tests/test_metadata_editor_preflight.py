"""Read-only self-check of the ME-11R OpenCode preflight utility."""
import sys
import tempfile
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parents[1]))
from metadata_editor_preflight import audit


class PreflightTest(unittest.TestCase):
    def test_rejects_empty_patch(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / 'empty.patch'
            p.write_text('')
            self.assertIn('NO_PATHS_OR_DUPLICATE_PATHS', audit(p)['errors'])

    def test_does_not_consider_unsafe_gate_true_to_be_ready(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / 'bad.patch'
            p.write_text('diff --git a/example b/example\n--- /dev/null\n+++ b/example\n'
                         '@@ -0,0 +1 @@\n+const val URI_CAS_CERTIFIED = true\n')
            self.assertIn('SOURCE_MUTATION_GATE_ENABLED', audit(p)['errors'])

    def test_detects_duplicate_paths(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / 'duplicate.patch'
            block = ('diff --git a/f b/f\n--- /dev/null\n+++ b/f\n'
                     '@@ -0,0 +1 @@\n+x\n')
            p.write_text(block + block)
            self.assertIn('NO_PATHS_OR_DUPLICATE_PATHS', audit(p)['errors'])


if __name__ == '__main__':
    unittest.main()
