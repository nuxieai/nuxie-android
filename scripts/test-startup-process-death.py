#!/usr/bin/env python3
"""Qualify durable public startup across an externally killed Android process.

Build/install the debug instrumentation APK first. This kills only the PID
recorded by this run of ai.nuxie.sdk.test, after verifying package ownership.
"""
import argparse
import json
from pathlib import Path
import re
import shutil
import subprocess
import time
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--device', required=True)
    parser.add_argument('--boundary', choices=['pending-profile', 'active-screen'], default='pending-profile')
    parser.add_argument('--adb', default=shutil.which('adb'))
    args = parser.parse_args()
    if not args.adb:
        parser.error('adb is not on PATH; pass --adb')
    adb = [args.adb, '-s', args.device]
    package = 'ai.nuxie.sdk.test'
    runner = package + '/androidx.test.runner.AndroidJUnitRunner'
    case = 'ai.nuxie.sdk.NuxiePublicStartupDeviceTest#processDeathRetainsThePublicTriggerUntilSignedProfileAdmission'
    run_id = str(uuid.uuid4())
    output = Path(__file__).resolve().parents[1] / 'build' / 'process-death' / run_id
    output.mkdir(parents=True)

    def command(*parts, check=True):
        return subprocess.run(adb + list(parts), check=check, capture_output=True, text=True, timeout=15)

    def invocation(phase):
        return adb + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', case,
                      '-e', 'nuxie_process_phase', phase, '-e', 'nuxie_process_run', run_id,
                      '-e', 'nuxie_process_boundary', args.boundary, runner]

    marker_path = f'files/process-startup-{run_id}/ready.json'
    with (output / 'seed.log').open('w') as log:
        seed = subprocess.Popen(invocation('seed'), stdout=log, stderr=subprocess.STDOUT)
        marker = None
        try:
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                result = command('shell', 'run-as', package, 'cat', marker_path, check=False)
                if result.returncode == 0:
                    marker = json.loads(result.stdout)
                    break
                if seed.poll() is not None:
                    raise RuntimeError(f'Seed terminated before its durable boundary; see {output}')
                time.sleep(0.1)
            if marker is None:
                raise RuntimeError(f'Seed did not reach its durable boundary; see {output}')
            pid = marker['pid']
            if not isinstance(pid, int) or pid <= 0:
                raise RuntimeError('Invalid seed PID')
            owned = command('shell', 'pidof', package).stdout.split()
            if str(pid) not in owned:
                raise RuntimeError('Seed PID no longer belongs to the test package')
            command('shell', 'run-as', package, 'kill', '-9', str(pid))
            seed.wait(timeout=15)
            if str(pid) in command('shell', 'pidof', package, check=False).stdout.split():
                raise RuntimeError('Seed process is still alive')
        finally:
            if seed.poll() is None:
                # Terminate the adb client; never guess which device process owns a failed seed.
                seed.terminate()
                seed.wait(timeout=15)

    with (output / 'recover.log').open('w') as log:
        recovered = subprocess.run(invocation('recover'), stdout=log, stderr=subprocess.STDOUT, timeout=60)
    result = (output / 'recover.log').read_text()
    if recovered.returncode != 0 or not re.search(r'^OK \(1 test\)$', result, re.MULTILINE):
        raise RuntimeError(f'Cross-process recovery failed; see {output / "recover.log"}')
    summary = {'boundary': args.boundary, 'run': run_id, 'seedPid': marker['pid'], 'retainedEventId': marker['eventId'],
               'result': 'passed', 'logs': str(output)}
    (output / 'result.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps(summary, indent=2))


if __name__ == '__main__':
    main()
