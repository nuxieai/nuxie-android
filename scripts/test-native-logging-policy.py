#!/usr/bin/env python3
"""Compile positive/negative probes for Android native diagnostic boundaries."""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
header = root / 'nuxie-android/src/main/cpp/nuxie_logging_policy.h'
compiler = os.environ.get('CC', 'cc')
blocked = ['__android_log_print', '__android_log_write', '__android_log_vprint',
           'printf', 'fprintf', 'vprintf', 'vfprintf', 'puts', 'fputs', 'perror',
           'dprintf', 'vdprintf', 'dup2', 'dup3', 'freopen']
with tempfile.TemporaryDirectory(prefix='nuxie-native-log-policy-') as directory:
    source = Path(directory) / 'probe.c'
    for android in (False, True):
        for name in blocked + ['log_native_warning']:
            source.write_text(f'#include "{header}"\nvoid {name}(void);\nvoid probe(void) {{ {name}(); }}\n')
            command = [compiler, '-fsyntax-only', '-x', 'c', str(source)]
            if android:
                command += ['-D__ANDROID__']
            result = subprocess.run(command, capture_output=True, text=True)
            rejected = android and name in blocked
            if rejected:
                assert result.returncode != 0 and 'poison' in result.stderr and name in result.stderr, result.stderr
            else:
                assert result.returncode == 0, result.stderr
print('Native logging policy: 15 Android bypasses rejected; callback and host diagnostics allowed.')
