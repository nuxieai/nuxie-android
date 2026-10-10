#!/usr/bin/env python3
"""Discover the existing top-level JUnit test classes without changing test bodies."""

import argparse
from pathlib import Path
import re


def classes(paths):
    result = set()
    for path in paths:
        source = Path(path).read_text()
        if not re.search(r"@(?:org\.junit\.)?Test\b", source):
            continue
        package = re.search(r"^package\s+([\w.]+)", source, re.MULTILINE)
        if not package:
            raise ValueError(f"JUnit input has no package: {path}")
        # Helpers and nested fixtures are indented; JVM JUnit owners are top-level.
        for name in re.findall(r"^(?:public\s+)?(?:final\s+)?class\s+(\w+)", source, re.MULTILINE):
            if name == "HostRenderSmokeTest":
                continue
            result.add(package.group(1) + "." + name)
    if not result:
        raise ValueError("No existing JUnit test classes were found")
    return sorted(result)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("sources", nargs="+")
    args = parser.parse_args()
    owners = ",\n".join("    " + name + ".class" for name in classes(args.sources))
    args.output.write_text(
        "package ai.nuxie.bazel;\n"
        "@org.junit.runner.RunWith(org.junit.runners.Suite.class)\n"
        "@org.junit.runners.Suite.SuiteClasses({\n" + owners + "\n})\n"
        "public final class SdkTestSuite {}\n"
    )


if __name__ == "__main__":
    main()
