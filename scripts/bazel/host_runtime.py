#!/usr/bin/env python3
"""Validate the explicitly selected host runtime before declaring its JNI input."""

import argparse
from pathlib import Path
import subprocess
import sys

SCRIPTING_SYMBOLS = (
    "nux_file_import_trusted_with_host_commands",
    "nux_file_import_android_vulkan_with_trusted_wgsl",
    "nux_player_step_result_host_command",
    "nux_player_step_result_host_value",
    "nux_player_step_result_host_value_child",
)


def validate(path):
    path = Path(path)
    if not path.is_absolute() or not path.is_file():
        raise ValueError("NUXIE_HOST_CAPI_LIB must name an existing absolute host library")
    if path.suffix not in (".dylib", ".so"):
        raise ValueError("The host runtime must be a macOS .dylib or Linux .so")
    symbols = subprocess.check_output(["nm", "-g", str(path)], text=True, stderr=subprocess.STDOUT)
    exported = {parts[-1].removeprefix("_") for line in symbols.splitlines()
                if len(parts := line.split()) >= 2 and parts[-2] in ("T", "W", "I")}
    missing = set(SCRIPTING_SYMBOLS) - exported
    if missing:
        raise ValueError("Host runtime lacks required scripting symbols: " + ", ".join(sorted(missing)))
    return path


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("library")
    try:
        validate(parser.parse_args().library)
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print(f"error: {error}", file=sys.stderr)
        sys.exit(1)
