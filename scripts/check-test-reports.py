#!/usr/bin/env python3
"""Fail CI if its fresh Maven report omits a required test suite."""

import os
import sys
from pathlib import Path
from xml.etree import ElementTree


def main() -> int:
    if len(sys.argv) < 3:
        print("usage: check-test-reports.py REPORT_DIR REQUIRED_SUITE...", file=sys.stderr)
        return 2

    report_dir = Path(sys.argv[1])
    expected = set(sys.argv[2:])
    reports = sorted(report_dir.glob("TEST-*.xml"))
    if not reports:
        print(f"No test XML reports in {report_dir}", file=sys.stderr)
        return 1

    rows = []
    found = set()
    total_tests = total_failures = total_errors = total_skipped = 0
    for report in reports:
        try:
            suite = ElementTree.parse(report).getroot()
            name = suite.attrib["name"].rsplit(".", 1)[-1]
            tests = int(suite.attrib["tests"])
            failures = int(suite.attrib.get("failures", 0))
            errors = int(suite.attrib.get("errors", 0))
            skipped = int(suite.attrib.get("skipped", 0))
        except (ElementTree.ParseError, KeyError, ValueError) as exc:
            print(f"Invalid test report {report}: {exc}", file=sys.stderr)
            return 1
        rows.append(f"{name}: tests={tests}, failures={failures}, errors={errors}, skipped={skipped}")
        if tests > skipped:
            found.add(name)
        total_tests += tests
        total_failures += failures
        total_errors += errors
        total_skipped += skipped

    missing = sorted(expected - found)
    summary = (
        f"{report_dir}: suites={len(reports)}, tests={total_tests}, "
        f"failures={total_failures}, errors={total_errors}, skipped={total_skipped}"
    )
    print(summary)
    for row in rows:
        print(row)
    if missing:
        print(f"Required suites missing or entirely skipped: {', '.join(missing)}", file=sys.stderr)
    if summary_path := os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(summary_path, "a", encoding="utf-8") as summary_file:
            summary_file.write(f"### Maven test reports\n\n{summary}\n\n")
            summary_file.write("\n".join(f"- {row}" for row in rows) + "\n")
            if missing:
                summary_file.write(f"\nMissing: {', '.join(missing)}\n")
    return 1 if missing or total_tests == total_skipped or total_failures or total_errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
