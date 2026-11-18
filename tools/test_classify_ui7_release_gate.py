"""Offline tests for the UI7 release gate classifier; stdlib only."""
from __future__ import annotations

import unittest

import classify_ui7_release_gate as classifier

MOSAIC = ":app:verifyUi7MosaicCertification"
POSTERS = ":app:verifyUi7EditorialPosterCertification"

TECHNICAL = """> Task :app:compileNormalReleaseKotlin
> Task :app:minifyNormalReleaseWithR8
> Task :app:packageNormalRelease
"""

RECEIPT_MOSAIC = ("UI7_RELEASE_GATE view=mosaic status=ARTISTIC_APPROVAL_PENDING pending=353")
RECEIPT_POSTERS = ("UI7_RELEASE_GATE view=posters status=ARTISTIC_APPROVAL_PENDING pending=353")


def pending_log(*receipts: str, extra_failed: str = "") -> str:
    return (
        "> Task :app:compileNormalReleaseKotlin\n"
        "> Task :app:minifyNormalReleaseWithR8\n"
        f"> Task {MOSAIC} FAILED\n"
        + extra_failed
        + "".join(receipt + "\n" for receipt in receipts)
        + "FAILURE: Build failed with an exception.\n"
        f"* What went wrong:\nExecution failed for task '{MOSAIC}'.\n"
        "BUILD FAILED in 2m 8s\n"
    )


class CertifiedGoTests(unittest.TestCase):
    def test_a_fully_approved_release_is_certified(self):
        log = TECHNICAL + f"> Task {MOSAIC}\n> Task {POSTERS}\nBUILD SUCCESSFUL in 3m\n"
        self.assertEqual((classifier.CERTIFIED_GO,
                          "release artifact produced and both certification gates passed"),
                         classifier.classify(log, 0))

    def test_a_success_that_skipped_the_gate_is_never_certified(self):
        # Removing the dependency would produce exactly this: a release that built
        # without asking, which must not be read as an approval.
        log = TECHNICAL + "BUILD SUCCESSFUL in 3m\n"
        state, reason = classifier.classify(log, 0)
        self.assertEqual(classifier.REGRESSION, state)
        self.assertIn("never ran", reason)

    def test_exit_code_zero_with_a_failed_task_is_a_regression(self):
        log = TECHNICAL + f"> Task {MOSAIC} FAILED\nBUILD SUCCESSFUL\n"
        state, _ = classifier.classify(log, 0)
        self.assertEqual(classifier.REGRESSION, state)


class ApprovalPendingTests(unittest.TestCase):
    def test_pending_artwork_alone_is_reported_as_pending(self):
        state, reason = classifier.classify(pending_log(RECEIPT_MOSAIC), 1)
        self.assertEqual(classifier.APPROVAL_PENDING, state)
        self.assertIn("mosaic=353", reason)

    def test_both_gates_pending_report_both_views(self):
        state, reason = classifier.classify(
            pending_log(RECEIPT_MOSAIC, RECEIPT_POSTERS), 1)
        self.assertEqual(classifier.APPROVAL_PENDING, state)
        self.assertIn("posters=353", reason)


class RegressionTests(unittest.TestCase):
    def test_a_compile_failure_is_never_mistaken_for_pending_artwork(self):
        log = ("> Task :app:compileNormalReleaseKotlin FAILED\n"
               + RECEIPT_MOSAIC + "\nBUILD FAILED in 40s\n")
        state, reason = classifier.classify(log, 1)
        self.assertEqual(classifier.REGRESSION, state)
        self.assertIn("outside the art certification gate", reason)

    def test_a_compile_failure_beside_a_pending_gate_is_a_regression(self):
        log = ("> Task :app:compileNormalReleaseKotlin FAILED\n"
               f"> Task {MOSAIC} FAILED\n" + RECEIPT_MOSAIC + "\nBUILD FAILED\n")
        state, _ = classifier.classify(log, 1)
        self.assertEqual(classifier.REGRESSION, state)

    def test_a_certification_failure_without_its_receipt_is_ambiguous(self):
        state, reason = classifier.classify(pending_log(), 1)
        self.assertEqual(classifier.REGRESSION, state)
        self.assertIn("without its structured approval receipt", reason)

    def test_a_gate_that_refused_before_compiling_is_not_pending(self):
        log = (f"> Task {MOSAIC} FAILED\n" + RECEIPT_MOSAIC + "\nBUILD FAILED\n")
        state, reason = classifier.classify(log, 1)
        self.assertEqual(classifier.REGRESSION, state)
        self.assertIn("never compiled", reason)

    def test_an_empty_log_is_a_regression(self):
        state, reason = classifier.classify("", 1)
        self.assertEqual(classifier.REGRESSION, state)
        self.assertIn("no build log", reason)

    def test_a_failure_without_any_failed_task_is_a_regression(self):
        state, _ = classifier.classify(TECHNICAL + "BUILD FAILED\n", 1)
        self.assertEqual(classifier.REGRESSION, state)

    def test_kotlin_error_lines_disqualify_a_pending_verdict(self):
        log = pending_log(RECEIPT_MOSAIC) + "e: file:///w/App.kt:12:5 unresolved reference\n"
        state, reason = classifier.classify(log, 1)
        self.assertEqual(classifier.REGRESSION, state)
        self.assertIn("Kotlin compilation errors", reason)


class TaskParsingTests(unittest.TestCase):
    def test_statuses_are_read_as_gradle_prints_them(self):
        log = ("> Task :app:a\n> Task :app:b FAILED\n> Task :app:c UP-TO-DATE\n"
               "> Task :app:d SKIPPED\n> Task :app:e NO-SOURCE\n")
        self.assertEqual(
            {":app:a": "SUCCESS", ":app:b": "FAILED", ":app:c": "UP-TO-DATE",
             ":app:d": "SKIPPED", ":app:e": "NO-SOURCE"},
            classifier.parse_tasks(log))

    def test_receipts_carry_the_pending_count(self):
        self.assertEqual({"mosaic": 353, "posters": 12},
                         classifier.parse_receipts(RECEIPT_MOSAIC + "\n"
                                                   + RECEIPT_POSTERS.replace("353", "12")))


if __name__ == "__main__":
    unittest.main()
