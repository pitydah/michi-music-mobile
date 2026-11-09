import tempfile
import unittest
from pathlib import Path

from tools.ui4_acceptance import acceptance


class Ui4AcceptanceTest(unittest.TestCase):
    def test_global_navigation_transitions_are_classified_by_enter_or_exit_type(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            nav = root / "app/src/main/java/org/michimusic/mobile/navigation/MichiNavHost.kt"
            nav.parent.mkdir(parents=True)
            nav.write_text(
                """
                enterTransition = { EnterTransition.None }
                exitTransition = { ExitTransition.None }
                popEnterTransition = { EnterTransition.None }
                popExitTransition = { ExitTransition.None }
                """,
                encoding="utf-8",
            )

            transition_checks = {
                result["name"]: result["ok"]
                for result in acceptance(root)
                if str(result["name"]).startswith("M52 global")
            }

            self.assertEqual(
                {
                    "M52 global enterTransition remains off": True,
                    "M52 global exitTransition remains off": True,
                    "M52 global popEnterTransition remains off": True,
                    "M52 global popExitTransition remains off": True,
                },
                transition_checks,
            )

    def test_player_fallback_and_default_mesh_checks_reject_stale_ui4_policy(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            sources = {
                "app/src/main/java/org/michimusic/mobile/ui/components/MiniPlayer.kt": """
                    .testTag(\"mini_player_endpoint_status\")
                    .aeroSharedNowPlayingArtwork(
                    onTextLayout = {}
                """,
                "app/src/main/java/org/michimusic/mobile/ui/screens/NowPlayingScreen.kt": """
                    AeroDynamicMeshBackground(
                    if (aeroDynamicMeshEnabled())
                    .aeroSharedNowPlayingArtwork(
                """,
                "app/src/main/java/org/michimusic/mobile/ui/motion/AeroSharedNowPlayingMotion.kt":
                    "shareArtwork = true",
                "app/src/main/java/org/michimusic/mobile/ui/motion/AeroDynamicPalette.kt": """
                    AeroDynamicMeshPerformanceCertified = false
                """,
                "app/build.gradle.kts": """
                    gradleProperty(\"michi.dynamicMeshEnabled\").orElse(\"false\")
                """,
            }
            for relative, content in sources.items():
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding="utf-8")

            relevant = {
                result["name"]: result["ok"]
                for result in acceptance(root)
                if result["name"].startswith(("MiniPlayer", "Now Playing artwork", "Player transition", "Dynamic Mesh"))
            }

            self.assertFalse(relevant["MiniPlayer removes measurement feedback"])
            self.assertFalse(relevant["MiniPlayer artwork stays local"])
            self.assertFalse(relevant["Now Playing artwork stays local"])
            self.assertFalse(relevant["Player transition policy forbids shared artwork"])
            self.assertFalse(relevant["Dynamic Mesh reads the build policy"])
            self.assertFalse(relevant["Dynamic Mesh defaults on"])
            self.assertFalse(relevant["Dynamic Mesh has no stale false certification gate"])


if __name__ == "__main__":
    unittest.main()
