#!/usr/bin/env python3
"""Run the opt-in R8 example qualification on an explicitly selected device.

Build first with :example-app:assembleRelease -PnuxieMinifyExampleRelease=true.
The APK must acquire covering system fonts and leave all release test seams inert.
On API 26+, its published fixture must draw ordinary color emoji through the C hook.
"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import time


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="An emulator/device owned by this task")
    parser.add_argument("--adb", default=shutil.which("adb") or str(Path(os.environ.get("ANDROID_HOME", "")) / "platform-tools/adb"))
    parser.add_argument("--apk", type=Path, default=root / "example-app/build/outputs/apk/release/example-app-release.apk")
    parser.add_argument("--output", type=Path, help="Save the focused qualification log")
    parser.add_argument("--skip-install", action="store_true", help="Repeat against the APK already installed")
    args = parser.parse_args()

    def adb(*command):
        return subprocess.check_output([args.adb, "-s", args.serial, *command], text=True, stderr=subprocess.STDOUT)

    if not args.skip_install:
        print(adb("install", "-r", str(args.apk)).strip())
    adb("shell", "am", "force-stop", "ai.nuxie.example")
    adb("logcat", "-c")
    launch = adb("shell", "am", "start", "-W", "-n", "ai.nuxie.example/.FontFallbackQualificationActivity")
    sdk = int(adb("shell", "getprop", "ro.build.version.sdk").strip())
    deadline = time.monotonic() + 15
    while True:
        log = adb("logcat", "-d", "-s", "NuxieFontR8:I", "AndroidRuntime:E", "Nuxie:W")
        passed = "PASS: covering fonts, kept JNI callback, inert release instrumentation" in log
        if sdk >= 26:
            passed = passed and "Published runtime C callback: color pixels=" in log
        failed = "FATAL EXCEPTION" in log or "install_system_font_fallback" in log
        if passed or failed or time.monotonic() >= deadline:
            break
        time.sleep(.1)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(launch + "\n" + log)
    if failed or not passed:
        raise SystemExit("FAIL: minified font fallback qualification\n" + launch + log)
    print(log.strip())
    print(f"PASS: {args.serial}, API {sdk}")


if __name__ == "__main__":
    main()
