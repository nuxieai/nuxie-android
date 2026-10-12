#!/usr/bin/env python3
"""Validate provisioned tools; never install or mutate a machine toolchain."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
ANDROID_FILES = ['platforms/android-36/android.jar', 'build-tools/36.0.0/aapt2', 'ndk/29.0.14206865/source.properties', 'cmake/3.22.1/bin/cmake']
NDK_VERSIONS = ['29.0.14206865']


def output(arguments):
    return subprocess.check_output(arguments, stderr=subprocess.STDOUT, text=True, timeout=30)


def validate(selected, environment=None):
    environment = os.environ if environment is None else environment
    facts = {'platform': selected}
    if selected == 'android':
        java = Path(environment.get('JAVA_HOME', ''))
        sdk = Path(environment.get('ANDROID_HOME') or environment.get('ANDROID_SDK_ROOT', ''))
        if not java.is_absolute() or not (java / 'bin/java').is_file():
            raise ValueError('Set JAVA_HOME to the provisioned Java 21 toolchain')
        version = output([str(java / 'bin/java'), '-version'])
        if not re.search(r'version "21(?:\.|\")', version):
            raise ValueError('Expected provisioned Java 21')
        if not sdk.is_absolute():
            raise ValueError('Set ANDROID_HOME to the provisioned Android SDK')
        for relative in ANDROID_FILES:
            if not (sdk / relative).is_file():
                raise ValueError('Missing provisioned Android input: ' + relative)
        for revision in NDK_VERSIONS:
            text = (sdk / ('ndk/' + revision + '/source.properties')).read_text()
            if not re.search(r'^Pkg.Revision\s*=\s*' + re.escape(revision) + r'\s*$', text, re.MULTILINE):
                raise ValueError('Provisioned Android NDK identity differs: ' + revision)
        facts.update(javaVersion=version.strip(), javaHome=str(java), androidHome=str(sdk))
    pass
    return facts


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--platform', choices=['portable', 'android', 'ios'], required=True)
    facts = validate(parser.parse_args().platform)
    directory = ROOT / 'output/pipeline/toolchain'
    for path in [ROOT / 'output', ROOT / 'output/pipeline', directory]:
        if path.is_symlink():
            raise ValueError('Refusing symlinked toolchain observation output')
    directory.mkdir(parents=True, exist_ok=True)
    (directory / 'observed.json').write_text(json.dumps(facts, indent=2) + '\n')


if __name__ == '__main__':
    main()
