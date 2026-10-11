"""Reject a native host test run that executed no test methods."""

import argparse
from pathlib import Path
import xml.etree.ElementTree as ET


def verify_report(path):
    report = ET.parse(path)
    cases = report.findall(".//testcase")
    skipped = sum(case.find("skipped") is not None for case in cases)
    if not cases or skipped == len(cases):
        raise ValueError("Host renderer executed no test methods (empty or all skipped)")
    if report.findall(".//failure") or report.findall(".//error"):
        raise ValueError("Host renderer report contains test failures or errors")
    return len(cases) - skipped, skipped


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path)
    args = parser.parse_args()
    try:
        executed, skipped = verify_report(args.report)
    except (OSError, ET.ParseError, ValueError) as error:
        parser.exit(1, str(error) + "\n")
    print(f"Host renderer executed {executed} tests ({skipped} skipped)")


if __name__ == "__main__":
    main()
