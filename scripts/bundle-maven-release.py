#!/usr/bin/env python3
"""Verify signed staged Maven artifacts and build a Central upload bundle."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile


def bundle(repository, version, public_key, fingerprint, output):
    if not re.fullmatch(r'\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?', version):
        raise ValueError('Expected a release version, such as 0.1.0 or 0.1.0-alpha.1')
    if version.endswith('-SNAPSHOT'):
        raise ValueError('Snapshot versions are not release bundles')
    fingerprint = fingerprint.upper()
    if not re.fullmatch(r'[0-9A-F]{40}|[0-9A-F]{64}', fingerprint):
        raise ValueError('Supply the full independently trusted signing-key fingerprint')
    prefix = Path('ai/nuxie/nuxie-android') / version
    stem = f'nuxie-android-{version}'
    artifacts = [stem + suffix for suffix in ('.aar', '.pom', '.module', '-sources.jar', '-javadoc.jar')]
    contents = {}
    with tempfile.TemporaryDirectory(prefix='nuxie-maven-signatures-') as temporary:
        home = Path(temporary)
        command = ['gpg', '--batch', '--no-tty', '--no-autostart', '--homedir', str(home)]
        subprocess.run(command + ['--import', str(public_key.resolve())], check=True, capture_output=True)
        for name in artifacts:
            data = (repository / prefix / name).read_bytes()
            signature = (repository / prefix / (name + '.asc')).read_bytes()
            candidate = home / name
            detached = home / (name + '.asc')
            candidate.write_bytes(data)
            detached.write_bytes(signature)
            verified = subprocess.run(command + ['--status-fd', '1', '--verify', str(detached), str(candidate)],
                                      capture_output=True, text=True)
            valid = [line.split() for line in verified.stdout.splitlines() if line.startswith('[GNUPG:] VALIDSIG ')]
            if verified.returncode or not any(fingerprint in (line[2], line[-1]) for line in valid):
                raise ValueError(f'Artifact signature does not match the trusted key: {name}')
            contents[name] = data
            contents[name + '.asc'] = signature
        pom = ET.fromstring(contents[stem + '.pom'])
        ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
        for field, expected in [('groupId', 'ai.nuxie'), ('artifactId', 'nuxie-android'), ('version', version), ('packaging', 'aar')]:
            if pom.findtext('m:' + field, namespaces=ns) != expected:
                raise ValueError(f'Unexpected Maven {field}')
        for field in ['name', 'description', 'url', 'licenses/license/name', 'licenses/license/url',
                      'developers/developer/name', 'scm/connection', 'scm/url']:
            if not (pom.findtext('/'.join('m:' + part for part in field.split('/')), namespaces=ns) or '').strip():
                raise ValueError(f'Missing Maven metadata: {field}')
        component = json.loads(contents[stem + '.module'])['component']
        if (component['group'], component['module'], component['version']) != ('ai.nuxie', 'nuxie-android', version):
            raise ValueError('Gradle module identity differs from the POM')
        for suffix, required in [('-sources.jar', 'ai/nuxie/sdk/Nuxie.kt'), ('-javadoc.jar', 'ai/nuxie/sdk/Nuxie.html'), ('.aar', 'lint.jar')]:
            with zipfile.ZipFile(home / (stem + suffix)) as archive:
                if not archive.read(required):
                    raise ValueError(f'Missing publication content: {required}')
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary_output = output.with_suffix(output.suffix + '.tmp')
    try:
        with zipfile.ZipFile(temporary_output, 'w', zipfile.ZIP_DEFLATED) as archive:
            for name, data in sorted(contents.items()):
                archive.writestr((prefix / name).as_posix(), data)
                for algorithm in ('md5', 'sha1', 'sha256', 'sha512'):
                    archive.writestr((prefix / (name + '.' + algorithm)).as_posix(), hashlib.new(algorithm, data).hexdigest())
        temporary_output.replace(output)
    finally:
        temporary_output.unlink(missing_ok=True)
    print(f'Verified signed bundle: {output}; SHA-256 {hashlib.sha256(output.read_bytes()).hexdigest()}')


if __name__ == '__main__':
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', required=True)
    parser.add_argument('--repository', type=Path, default=root / 'build/maven-repository')
    parser.add_argument('--public-key', type=Path, required=True)
    parser.add_argument('--fingerprint', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    bundle(args.repository, args.version, args.public_key, args.fingerprint, args.output)
