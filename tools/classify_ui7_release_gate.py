#!/usr/bin/env python3
"""Classify a UI7 release build into one of three states.

The workflow runs the release build separately and hands the exit code and the
captured log here. This script never changes the build result: it only reads the
evidence Gradle already produced and decides whether the refusal was the art
review barrier or something worse.

The classification is fail-closed. Anything it cannot prove is a regression:
a missing log, a failed task that is not a certification task, a certification
failure without its structured receipt, a release build that never compiled, or
an exit code that disagrees with the log.
"""
from __future__ import annotations

import argparse
import re
from pathlib import Path

CERTIFIED_GO = "RELEASE_CERTIFIED_GO"
APPROVAL_PENDING = "ARTISTIC_APPROVAL_PENDING"
REGRESSION = "RELEASE_REGRESSION_FAIL"

# The two gates that refuse a release whose artwork still waits for review. The
# value is the view name the validator puts in its receipt.
CERTIFICATION_TASKS = {
    ":app:verifyUi7MosaicCertification": "mosaic",
    ":app:verifyUi7EditorialPosterCertification": "posters",
}

TASK_LINE = re.compile(r"^> Task (\S+?)(?: (FAILED|UP-TO-DATE|SKIPPED|NO-SOURCE|FROM-CACHE))?$")
RECEIPT_LINE = re.compile(
    r"^UI7_RELEASE_GATE view=(\w+) status=ARTISTIC_APPROVAL_PENDING pending=(\d+)$")
# Evidence that the release code itself was built before the gate spoke. If the
# gate were wired back onto pre<Variant>Build this would be absent, and a refusal
# that never compiled anything cannot be certified as "only waiting for art".
RELEASE_TECHNICAL_WORK = re.compile(r"^:app:(?:compile|minify)\S*Release\S*$")


def parse_tasks(log: str) -> dict[str, str]:
    """Task name -> status as Gradle printed it; an unmarked task ran fine."""
    tasks: dict[str, str] = {}
    for line in log.splitlines():
        match = TASK_LINE.match(line.rstrip())
        if match:
            name, status = match.group(1), match.group(2)
            tasks[name] = status or "SUCCESS"
    return tasks


def parse_receipts(log: str) -> dict[str, int]:
    receipts: dict[str, int] = {}
    for line in log.splitlines():
        match = RECEIPT_LINE.match(line.strip())
        if match:
            receipts[match.group(1)] = int(match.group(2))
    return receipts


def classify(log: str, exit_code: int) -> tuple[str, str]:
    """Return the classification and the reason that produced it."""
    if not log.strip():
        return REGRESSION, "no build log was captured"

    tasks = parse_tasks(log)
    failed = {name for name, status in tasks.items() if status == "FAILED"}
    receipts = parse_receipts(log)

    if exit_code == 0:
        if failed:
            return REGRESSION, "exit code 0 but the log records failed tasks: " + \
                ", ".join(sorted(failed))
        missing = sorted(task for task in CERTIFICATION_TASKS if task not in tasks)
        if missing:
            return REGRESSION, "release compiled but the certification gate never ran: " + \
                ", ".join(missing)
        return CERTIFIED_GO, "release artifact produced and both certification gates passed"

    if not failed:
        return REGRESSION, f"exit code {exit_code} without a failed task in the log"

    unexpected = sorted(failed - set(CERTIFICATION_TASKS))
    if unexpected:
        return REGRESSION, "failed tasks outside the art certification gate: " + \
            ", ".join(unexpected)

    for task in sorted(failed):
        view = CERTIFICATION_TASKS[task]
        if view not in receipts:
            return REGRESSION, f"{task} failed without its structured approval receipt"

    if not any(RELEASE_TECHNICAL_WORK.match(name) for name in tasks):
        return REGRESSION, "release code was never compiled before the gate refused"

    if re.search(r"^e: file://", log, re.MULTILINE):
        return REGRESSION, "Kotlin compilation errors are present in the log"

    pending = sorted(receipts.values())
    return APPROVAL_PENDING, (
        "release refused only by pending visual approval: "
        + ", ".join(f"{view}={receipts[view]}" for view in sorted(receipts))
        + f" (tasks: {', '.join(sorted(failed))}; receipt totals {pending})"
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--log", required=True, type=Path)
    parser.add_argument("--exit-code", required=True, type=int)
    parser.add_argument("--summary", type=Path, help="append a markdown summary here")
    parser.add_argument("--github-output", type=Path, help="write classification=<state>")
    arguments = parser.parse_args()

    text = arguments.log.read_text(encoding="utf-8", errors="replace") \
        if arguments.log.exists() else ""
    state, reason = classify(text, arguments.exit_code)

    print(f"UI7_RELEASE_CLASSIFICATION {state}")
    print(f"reason: {reason}")

    if arguments.github_output:
        with arguments.github_output.open("a", encoding="utf-8") as handle:
            handle.write(f"classification={state}\n")

    if arguments.summary:
        lines = [
            "## UI7 Release Gate",
            "",
            f"- classification: `{state}`",
            f"- release build exit code: `{arguments.exit_code}`",
            f"- reason: {reason}",
        ]
        if state == CERTIFIED_GO:
            lines.append("- release: `RELEASE_CERTIFIED_GO` — the catalogue is approved")
        elif state == APPROVAL_PENDING:
            lines.append("- release: `RELEASE_NO_GO` — artwork still awaits visual review")
            lines.append("- technical integration: `TECHNICAL_INTEGRATION_GO`")
        else:
            lines.append("- release: `RELEASE_NO_GO` — a real regression must be fixed")
        lines.append("")
        with arguments.summary.open("a", encoding="utf-8") as handle:
            handle.write("\n".join(lines) + "\n")

    return 0 if state in (CERTIFIED_GO, APPROVAL_PENDING) else 2


if __name__ == "__main__":
    raise SystemExit(main())
