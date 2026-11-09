"""Tests for UI4 preflight with UI3.2 already integrated (no old patch)."""
from __future__ import annotations
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

PREFLIGHT = Path(__file__).resolve().parents[1] / "ui4_preflight.py"
APP = Path("app/src/main/java/org/michimusic/mobile")

class UI4PreflightTest(unittest.TestCase):
    def run_command(self, args, *, check=True):
        result = subprocess.run(args, cwd=self.repo, text=True, capture_output=True)
        if check and result.returncode:
            self.fail(f"{args}: {result.stderr} {result.stdout}")
        return result

    def setUp(self):
        temp = tempfile.TemporaryDirectory(prefix="ui4-ui32-preflight-")
        self.addCleanup(temp.cleanup)
        base = Path(temp.name)
        self.repo = base / "michi"
        self.repo.mkdir()
        self.run_command(["git", "init", "-q"])
        self.run_command(["git", "config", "user.name", "UI4 Test"])
        self.run_command(["git", "config", "user.email", "ui4@example.invalid"])
        for path, content in {
            "ui/screens/AlbumDetailScreen.kt": "trackPresentation.tracks\n",
            "ui/components/AlbumMosaicGrid.kt": "state: LazyGridState\n",
            "ui/components/BottomNavBar.kt": "private fun DockTab(\nDockLayout.TWO_BY_TWO\n",
            "navigation/MichiNavHost.kt": 'route = "album/{albumId}"\n',
            "ui/screens/AlbumsScreen.kt": "MichiCoverFlowHost(\nstate = albumGridState\n",
        }.items():
            f = self.repo / APP / path
            f.parent.mkdir(parents=True, exist_ok=True)
            f.write_text(content)
        (self.repo / "app/build.gradle.kts").write_text("plugins {}\n")
        (self.repo / "tools").mkdir()
        (self.repo / "tools/ui4_acceptance.py").write_text('import json\nprint(json.dumps({"passed": 1}))\n')
        (self.repo / "source.txt").write_text("integrated UI3.2\n")
        self.run_command(["git", "add", "."])
        self.run_command(["git", "commit", "-q", "-m", "UI3.2 integrated"])
        (self.repo / "source.txt").write_text("integrated UI3.2 with UI4\n")
        patch_content = self.run_command(["git", "diff", "--", "source.txt"]).stdout
        self.ui4 = base / "ui4.patch"
        self.ui4.write_text(patch_content)
        self.run_command(["git", "reset", "--hard", "HEAD"])

    def check_preflight(self, *args):
        response = self.run_command([sys.executable, str(PREFLIGHT), "--root", str(self.repo),
                                     "--ui4", str(self.ui4), "--json", *args], check=False)
        return response, json.loads(response.stdout)

    def test_valid_integrated_ui32_checkout_is_never_modified(self):
        response, status = self.check_preflight()
        self.assertEqual(response.returncode, 0, status)
        self.assertEqual(status["status"], "READY_FOR_GRADLE")
        self.assertEqual(status["ui3"], "INTEGRATED_UI3_2_NO_REAPPLY")
        self.assertEqual(status["ui4"], "APPLIED_TO_TEMP_ONLY")
        self.assertEqual((self.repo / "source.txt").read_text(), "integrated UI3.2\n")

    def test_old_ui3_argument_is_ignored_not_applied(self):
        response, status = self.check_preflight("--ui3", "/absent/legacy-ui3.patch")
        self.assertEqual(response.returncode, 0, status)
        self.assertIn("ignored", status["legacy_ui3_note"])

    def test_committed_ui4_is_detected(self):
        self.run_command(["git", "apply", str(self.ui4)])
        self.run_command(["git", "add", "."])
        self.run_command(["git", "commit", "-q", "-m", "UI4"])
        response, status = self.check_preflight()
        self.assertEqual(response.returncode, 0, status)
        self.assertEqual(status["ui4"], "ALREADY_PRESENT")

    def test_uncommitted_ui4_snapshot_is_detected(self):
        self.run_command(["git", "apply", str(self.ui4)])
        response, status = self.check_preflight("--snapshot-worktree")
        self.assertEqual(response.returncode, 0, status)
        self.assertEqual(status["ui4"], "ALREADY_PRESENT")

    def test_rejects_missing_ui32_contracts(self):
        f = self.repo / APP / "ui/components/AlbumMosaicGrid.kt"
        f.write_text("old API\n")
        self.run_command(["git", "add", "."])
        self.run_command(["git", "commit", "-q", "-m", "bad base"])
        response, status = self.check_preflight()
        self.assertNotEqual(response.returncode, 0)
        self.assertIn("UI3.2", status["error"])

    def test_rejects_patch_conflicts(self):
        self.ui4.write_text(self.ui4.read_text().replace("-integrated UI3.2", "-wrong base"))
        response, status = self.check_preflight()
        self.assertNotEqual(response.returncode, 0)
        self.assertIn("conflicts", status["error"])

if __name__ == "__main__":
    unittest.main()
