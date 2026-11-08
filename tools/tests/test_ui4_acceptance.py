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


if __name__ == "__main__":
    unittest.main()
