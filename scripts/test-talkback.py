#!/usr/bin/env python3
"""Run signed-scene TalkBack probes and restore the emulator accessibility settings."""
import argparse
import os
from pathlib import Path
import re
import shlex
import subprocess
import time

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = "com.google.android.marvin.talkback"
SERVICE = f"{PACKAGE}/{PACKAGE}.TalkBackService"
NOTIFICATION_PERMISSION = "android.permission.POST_NOTIFICATIONS"
KEYS = ("enabled_accessibility_services", "accessibility_enabled", "touch_exploration_enabled")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--input-device", required=True)
    parser.add_argument("--repeat", type=int, default=1)
    parser.add_argument("--scenario", choices=("entry", "entry-after-editor", "home-return", "virtual-home-return", "roles", "activation", "activation-error", "native-slider", "virtual-slider"), default="roles")
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

    def notification_state():
        matches = re.findall(r"android\.permission\.POST_NOTIFICATIONS: granted=(true|false), flags=\[([^\]]*)\]",
                             shell("dumpsys", "package", PACKAGE))
        if len(matches) != 1:
            raise RuntimeError("Expected one TalkBack runtime notification permission record")
        return matches[0]

    if shell("am", "get-current-user") != "0":
        parser.error("This probe supports the dedicated emulator's primary user only")
    if int(shell("getprop", "ro.build.version.sdk")) < 34:
        parser.error("The hardware-input probe requires API 34+")
    name = shell("su", "0", "cat", f"/sys/class/input/{Path(args.input_device).name}/device/name")
    if name != "virtio_input_multi_touch_1":
        parser.error("input-device must be the primary virtio emulator touchscreen")
    if not shell("pm", "path", PACKAGE).startswith("package:"):
        parser.error("Install TalkBack on the emulator first")
    previous = {key: shell("settings", "get", "secure", key) for key in KEYS}
    previous_notification = notification_state()
    try:
        # TalkBack's own setup dialog otherwise remains behind each test Activity,
        # introducing unrelated window/focus transitions when a presentation closes.
        if previous_notification[0] == "false":
            shell("pm", "grant", "--user", "0", PACKAGE, NOTIFICATION_PERMISSION)
            if notification_state()[0] != "true":
                raise RuntimeError("TalkBack notification permission was not granted")
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
        method = {
            "virtual-home-return": "signedAuthoredRolesRestoreTalkBackSliderAfterHome",
            "home-return": "signedAuthoredRolesRestoreTalkBackEditorAfterHome",
            "entry-after-editor": "signedAuthoredRolesEnterAtHeadingAfterNativeEditor",
            "entry": "signedAuthoredRolesEnterAtHeadingWithTalkBack",
            "native-slider": "nativeSliderAcceptsTalkBackAdjustmentsAfterRepeatedTraversal",
            "virtual-slider": "virtualSliderAcceptsTalkBackAdjustmentsAfterRepeatedTraversal",
            "roles": "signedAuthoredRolesExposeSecureEditorAndDurableNativeActions",
            "activation": "signedSemanticActivationIsDurableBeforeAuthoredNavigation",
            "activation-error": "failedSignedSemanticActionDoesNotCommitPartialResponses",
        }[args.scenario]
        test_class = "TalkBackNativeSliderDeviceTest" if args.scenario in ("native-slider", "virtual-slider") else "PublishedTextInputDeviceTest"
        for iteration in range(args.repeat):
            print(f"TalkBack {args.scenario} pass {iteration + 1}/{args.repeat}", flush=True)
            subprocess.run([str(ROOT / "gradlew"), ":nuxie-android:connectedDebugAndroidTest",
                f"-Pandroid.testInstrumentationRunnerArguments.class=ai.nuxie.sdk.presentation.{test_class}#{method}",
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
            except Exception as error:
                failures.append(f"{key}: {error}")
        if previous_notification[0] == "false":
            try:
                shell("pm", "revoke", "--user", "0", PACKAGE, NOTIFICATION_PERMISSION)
            except Exception as error:
                failures.append(f"notification permission: {error}")
        # Revocation can restart an originally enabled service; allow its state to settle.
        deadline = time.monotonic() + 10
        while True:
            try:
                mismatches = [key for key, value in previous.items()
                              if shell("settings", "get", "secure", key) != value]
                if notification_state() != previous_notification:
                    mismatches.append("notification permission or flags")
            except Exception as error:
                failures.append(f"restoration readback: {error}")
                break
            if not mismatches:
                break
            if time.monotonic() >= deadline:
                failures.append("readback differs: " + ", ".join(mismatches))
                break
            time.sleep(0.2)
        if failures:
            raise RuntimeError("Failed to restore emulator settings: " + "; ".join(failures))
        print("Restored and verified original accessibility settings and TalkBack notification permission/flags", flush=True)


if __name__ == "__main__":
    main()
