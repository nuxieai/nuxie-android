#!/usr/bin/env python3
"""Run the signed-role TalkBack probe and restore the emulator accessibility settings."""
import argparse
import os
from pathlib import Path
import re
import shlex
import subprocess
import time

ROOT = Path(__file__).resolve().parent.parent
SERVICE = "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService"
KEYS = ("enabled_accessibility_services", "accessibility_enabled", "touch_exploration_enabled")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--input-device", required=True)
    parser.add_argument("--repeat", type=int, default=1)
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-[0-9]+", args.serial):
        parser.error("Use a dedicated rooted emulator, not a physical or user device")
    if not re.fullmatch(r"/dev/input/event[0-9]+", args.input_device):
        parser.error("input-device must name an evdev event device")
    if not 1 <= args.repeat <= 10:
        parser.error("repeat must be between 1 and 10")

    def shell(*command):
        return subprocess.run(["adb", "-s", args.serial, "shell", shlex.join(command)],
                              check=True, text=True, capture_output=True, timeout=30).stdout.strip()

    if int(shell("getprop", "ro.build.version.sdk")) < 34:
        parser.error("The hardware-input probe requires API 34+")
    name = shell("su", "0", "cat", f"/sys/class/input/{Path(args.input_device).name}/device/name")
    if name != "virtio_input_multi_touch_1":
        parser.error("input-device must be the primary virtio emulator touchscreen")
    if not shell("pm", "path", "com.google.android.marvin.talkback").startswith("package:"):
        parser.error("Install TalkBack on the emulator first")
    previous = {key: shell("settings", "get", "secure", key) for key in KEYS}
    try:
        services = [] if previous[KEYS[0]] in ("null", "") else previous[KEYS[0]].split(":")
        if SERVICE not in services:
            services.append(SERVICE)
        shell("settings", "put", "secure", KEYS[0], ":".join(services))
        shell("settings", "put", "secure", "accessibility_enabled", "1")
        deadline = time.monotonic() + 20
        while shell("settings", "get", "secure", "touch_exploration_enabled") != "1":
            if time.monotonic() >= deadline:
                raise RuntimeError("TalkBack did not enable touch exploration")
            time.sleep(0.2)
        environment = dict(os.environ, ANDROID_SERIAL=args.serial)
        for iteration in range(args.repeat):
            print(f"TalkBack signed-role pass {iteration + 1}/{args.repeat}", flush=True)
            subprocess.run([str(ROOT / "gradlew"), ":nuxie-android:connectedDebugAndroidTest",
                "-Pandroid.testInstrumentationRunnerArguments.class=ai.nuxie.sdk.presentation.PublishedTextInputDeviceTest#signedAuthoredRolesExposeSecureEditorAndDurableNativeActions",
                "-Pandroid.testInstrumentationRunnerArguments.nuxieTalkBackQualification=true",
                f"-Pandroid.testInstrumentationRunnerArguments.nuxieTalkBackInputDevice={args.input_device}"],
                cwd=ROOT, env=environment, check=True, timeout=600)
    finally:
        failures = []
        for key, value in previous.items():
            try:
                if value == "null":
                    shell("settings", "delete", "secure", key)
                else:
                    shell("settings", "put", "secure", key, value)
                if shell("settings", "get", "secure", key) != value:
                    raise RuntimeError("readback differs")
            except Exception as error:
                failures.append(f"{key}: {error}")
        if failures:
            raise RuntimeError("Failed to restore accessibility settings: " + "; ".join(failures))
        print("Restored and verified original emulator accessibility settings", flush=True)


if __name__ == "__main__":
    main()
