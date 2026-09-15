#!/usr/bin/env python3
"""Exercise Gradle signing and bundle admission with an isolated temporary key."""
import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('bundle_release', root / 'scripts/bundle-maven-release.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
with tempfile.TemporaryDirectory(prefix='nxpgp-', dir='/tmp') as temporary:
    work = Path(temporary)
    # Keep the Unix socket path below macOS's limit for gpg-agent.
    home = work / 'gnupg'
    home.mkdir(mode=0o700)
    gpg = ['gpg', '--batch', '--no-tty', '--homedir', str(home)]
    subprocess.run(gpg + ['--pinentry-mode', 'loopback', '--passphrase', '', '--quick-generate-key',
                         'Nuxie publication test <fixture@example.invalid>', 'rsa2048', 'sign', '1d'],
                   check=True, capture_output=True)
    keys = subprocess.run(gpg + ['--with-colons', '--list-keys'], check=True, capture_output=True, text=True).stdout
    fingerprint = next(line.split(':')[9] for line in keys.splitlines() if line.startswith('fpr:'))
    public_key = work / 'public.asc'
    public_key.write_bytes(subprocess.run(gpg + ['--armor', '--export', fingerprint], check=True, capture_output=True).stdout)
    environment = os.environ.copy()
    environment['NUXIE_SIGNING_KEY'] = subprocess.run(gpg + ['--armor', '--export-secret-keys', fingerprint],
                                                    check=True, capture_output=True, text=True).stdout
    environment['NUXIE_SIGNING_PASSWORD'] = ''
    logs = root / 'build/maven-signing-test'
    logs.mkdir(parents=True, exist_ok=True)
    with (logs / 'gradle.log').open('w') as output:
        subprocess.run([str(root / 'gradlew'), '--no-daemon', ':nuxie-android:publishReleasePublicationToStagingRepository'],
                       cwd=root, env=environment, check=True, stdout=output, stderr=subprocess.STDOUT)
    repository = work / 'repository'
    shutil.copytree(root / 'build/maven-repository', repository)
    versions = list((repository / 'ai/nuxie/nuxie-android').glob('*/nuxie-android-*.pom'))
    if len(versions) != 1:
        raise RuntimeError('Use a clean staging repository with one candidate version')
    version = versions[0].parent.name
    bundle = work / 'central.zip'
    module.bundle(repository, version, public_key, fingerprint, bundle)
    print('Gradle-generated signatures and complete bundle passed', flush=True)
    def rejected(label, key_fingerprint=fingerprint):
        try:
            module.bundle(repository, version, public_key, key_fingerprint, work / 'rejected.zip')
        except (ValueError, FileNotFoundError):
            if (work / 'rejected.zip').exists():
                raise AssertionError('Rejected input left an uploadable output')
            print(label + ': rejected', flush=True)
        else:
            raise AssertionError(label + ': incorrectly accepted')
    rejected('Wrong trusted fingerprint', '0' * 40)
    aar = versions[0].with_suffix('.aar')
    original = aar.read_bytes()
    aar.write_bytes(original + b'tampered')
    rejected('Modified artifact bytes')
    aar.write_bytes(original)
    signature = aar.with_suffix('.aar.asc')
    signature.unlink()
    rejected('Missing artifact signature')
print('Maven signing tests passed; temporary private key removed', flush=True)
