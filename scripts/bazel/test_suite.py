#!/usr/bin/env python3
"""Discover the existing top-level JUnit test classes without changing test bodies."""

import argparse
from pathlib import Path
import re


def test_owners(source):
    # Ignore comments and literals when finding class bodies and annotations.
    source = re.sub(
        r'/\*[\s\S]*?\*/|//[^\n]*|"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|`[^`]*`',
        lambda match: re.sub(r"[^\n]", " ", match.group()), source,
    )
    declarations = list(re.finditer(r"^(?:public\s+)?(?:final\s+)?class\s+(\w+)", source, re.MULTILINE))
    for index, declaration in enumerate(declarations):
        limit = declarations[index + 1].start() if index + 1 < len(declarations) else len(source)
        body = source.find("{", declaration.end(), limit)
        if body < 0:
            continue
        depth = 0
        for position in range(body, limit):
            character = source[position]
            if character == "{":
                depth += 1
            elif character == "}":
                depth -= 1
                if depth == 0:
                    break
            elif character == "@" and depth == 1 and re.match(r"@(?:org\.junit\.)?Test\b", source[position:]):
                yield declaration.group(1)
                break


def classes(paths):
    result = set()
    for path in paths:
        source = Path(path).read_text()
        if not re.search(r"@(?:org\.junit\.)?Test\b", source):
            continue
        package = re.search(r"^package\s+([\w.]+)", source, re.MULTILINE)
        if not package:
            raise ValueError(f"JUnit input has no package: {path}")
        # A helper can be top-level in a test file without owning any test methods.
        for name in test_owners(source):
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
