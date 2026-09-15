#!/usr/bin/env python3
"""Prepare a tagged SDK release and manage its Central deployment without retries."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import urllib.parse
import urllib.request
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CENTRAL = 'https://central.sonatype.com/api/v1/publisher/'


def git(*arguments):
    return subprocess.run(['git', *arguments], cwd=ROOT, check=True, capture_output=True, text=True).stdout.strip()


def tagged_source(version):
    if not re.fullmatch(r'\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?', version) or version.endswith('-SNAPSHOT'):
        raise ValueError('Expected a non-snapshot release version')
    if git('status', '--porcelain'):
        raise ValueError('Release preparation requires a clean SDK checkout')
    declared = re.findall(r'^version = "([^"]+)"$', (ROOT / 'build.gradle.kts').read_text(), re.MULTILINE)
    if declared != [version]:
        raise ValueError('Requested version must equal the SDK build version')
    tag = 'v' + version
    git('fetch', 'origin', 'main', 'tag', tag)
    head = git('rev-parse', 'HEAD')
    if git('rev-parse', f'refs/tags/{tag}^{{commit}}') != head:
        raise ValueError('Check out the exact remotely published release tag')
    git('merge-base', '--is-ancestor', head, 'origin/main')
    return head


def save(path, value, exclusive=False):
    data = json.dumps(value, indent=2) + '\n'
    if exclusive:
        with path.open('x') as stream:
            stream.write(data)
    else:
        temporary = path.with_suffix(path.suffix + '.tmp')
        temporary.write_text(data)
        temporary.replace(path)


def prepare(version, public_key, fingerprint, directory):
    head = tagged_source(version)
    if os.environ.get('NUXIE_RUNTIME_USE_LOCAL'):
        raise ValueError('Release preparation must use the public pinned runtime')
    if not os.environ.get('NUXIE_SIGNING_KEY'):
        raise ValueError('NUXIE_SIGNING_KEY is required for release preparation')
    directory.mkdir(parents=True, exist_ok=False)
    commands = [
        [str(ROOT / 'gradlew'), '--no-daemon', ':nuxie-android:test', ':nuxie-android:apiCheck',
         ':nuxie-android:lint', ':example-app:assembleDebug', ':nuxie-android:assembleRelease',
         ':nuxie-android:publishReleasePublicationToStagingRepository'],
        [sys.executable, str(ROOT / 'scripts/test-maven-consumer.py'), '--version', version],
        [sys.executable, str(ROOT / 'scripts/bundle-maven-release.py'), '--version', version,
         '--public-key', str(public_key.resolve()), '--fingerprint', fingerprint,
         '--output', str((directory / 'bundle.zip').resolve())],
    ]
    for index, command in enumerate(commands):
        with (directory / f'check-{index + 1}.log').open('w') as log:
            subprocess.run(command, cwd=ROOT, check=True, stdout=log, stderr=subprocess.STDOUT)
    if tagged_source(version) != head:
        raise ValueError('Release source changed during preparation')
    data = (directory / 'bundle.zip').read_bytes()
    manifest = {'version': version, 'sourceCommit': head, 'bundleSha256': hashlib.sha256(data).hexdigest(),
                'signingFingerprint': fingerprint.upper(), 'checks': commands}
    save(directory / 'release.json', manifest, exclusive=True)
    print(f'Qualified release manifest: {directory / "release.json"}')


class NoRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        raise ValueError('Central request redirected; refusing to forward credentials')


def request(endpoint, body=b'', content_type='application/octet-stream'):
    username = os.environ.get('NUXIE_CENTRAL_USERNAME')
    password = os.environ.get('NUXIE_CENTRAL_PASSWORD')
    if not username or not password:
        raise ValueError('NUXIE_CENTRAL_USERNAME and NUXIE_CENTRAL_PASSWORD are required')
    authorization = base64.b64encode((username + ':' + password).encode()).decode()
    req = urllib.request.Request(CENTRAL + endpoint, data=body, method='POST',
                                 headers={'Authorization': 'Bearer ' + authorization, 'Content-Type': content_type})
    with urllib.request.build_opener(NoRedirects).open(req, timeout=60) as response:
        return response.read()


def upload(manifest_path):
    manifest = json.loads(manifest_path.read_text())
    data = (manifest_path.parent / 'bundle.zip').read_bytes()
    if hashlib.sha256(data).hexdigest() != manifest['bundleSha256']:
        raise ValueError('Bundle changed after release qualification')
    if tagged_source(manifest['version']) != manifest['sourceCommit']:
        raise ValueError('Manifest does not describe the current release tag')
    if not os.environ.get('NUXIE_CENTRAL_USERNAME') or not os.environ.get('NUXIE_CENTRAL_PASSWORD'):
        raise ValueError('Central credentials are required before starting an upload')
    receipt_path = manifest_path.parent / 'deployment.json'
    name = f"nuxie-android-{manifest['version']}-{manifest['bundleSha256'][:16]}"
    receipt = {'bundleSha256': manifest['bundleSha256'], 'version': manifest['version'],
               'sourceCommit': manifest['sourceCommit'], 'deploymentName': name, 'state': 'UPLOAD_STARTED'}
    # Exclusive creation prevents concurrent or uncertain requests being retried.
    save(receipt_path, receipt, exclusive=True)
    boundary = 'nuxie-' + uuid.uuid4().hex
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="bundle"; filename="bundle.zip"\r\n'
            'Content-Type: application/octet-stream\r\n\r\n').encode() + data + f'\r\n--{boundary}--\r\n'.encode()
    deployment = request('upload?' + urllib.parse.urlencode({'name': name, 'publishingType': 'USER_MANAGED'}),
                         body, 'multipart/form-data; boundary=' + boundary).decode().strip()
    receipt['deploymentId'] = str(uuid.UUID(deployment))
    receipt['state'] = 'UPLOADED'
    save(receipt_path, receipt)
    print(f'Uploaded for Central validation: {receipt_path}')


def status(receipt_path, publish=False):
    receipt = json.loads(receipt_path.read_text())
    if 'deploymentId' not in receipt:
        raise ValueError('Upload outcome is uncertain. Find the named deployment in Central and use recover-upload; do not upload again.')
    deployment = str(uuid.UUID(receipt['deploymentId']))
    response = json.loads(request('status?' + urllib.parse.urlencode({'id': deployment})))
    if response.get('deploymentId') != deployment or response.get('deploymentName') != receipt['deploymentName']:
        raise ValueError('Central response does not match the saved deployment')
    state = response['deploymentState']
    if state not in {'PENDING', 'VALIDATING', 'VALIDATED', 'PUBLISHING', 'PUBLISHED', 'FAILED'}:
        raise ValueError('Unknown Central deployment state')
    receipt['state'] = state
    receipt['lastStatus'] = response
    save(receipt_path, receipt)
    if publish and state == 'VALIDATED':
        request('deployment/' + deployment)
        receipt['state'] = 'PUBLISH_REQUESTED'
        save(receipt_path, receipt)
    elif publish and state not in {'PUBLISHING', 'PUBLISHED'}:
        raise ValueError(f'Central deployment is not ready to publish: {state}')
    print(json.dumps({'deploymentId': deployment, 'state': receipt['state']}))




def recover_upload(receipt_path, deployment_id):
    receipt = json.loads(receipt_path.read_text())
    if 'deploymentId' in receipt:
        raise ValueError('This receipt already identifies a deployment; use status')
    deployment = str(uuid.UUID(deployment_id))
    response = json.loads(request('status?' + urllib.parse.urlencode({'id': deployment})))
    if response.get('deploymentId') != deployment or response.get('deploymentName') != receipt['deploymentName']:
        raise ValueError('Recovery deployment does not match the saved upload attempt')
    receipt['deploymentId'] = deployment
    receipt['state'] = 'RECOVERED'
    save(receipt_path, receipt)
    status(receipt_path)


def qualify_public(receipt_path):
    status(receipt_path)
    receipt = json.loads(receipt_path.read_text())
    if receipt['state'] != 'PUBLISHED':
        raise ValueError('Central must report PUBLISHED before public qualification')
    manifest = json.loads((receipt_path.parent / 'release.json').read_text())
    receipt.pop('publicConsumerQualified', None)
    save(receipt_path, receipt)
    archive_path = receipt_path.parent / 'bundle.zip'
    digest = hashlib.sha256(archive_path.read_bytes()).hexdigest()
    if digest != receipt['bundleSha256'] or digest != manifest['bundleSha256']:
        raise ValueError('Prepared bundle no longer matches the deployment')
    version = manifest['version']
    if version != receipt['version'] or not re.fullmatch(r'\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?', version):
        raise ValueError('Invalid deployment version')
    repository = 'https://repo.maven.apache.org/maven2'
    prefix = f'ai/nuxie/nuxie-android/{version}/nuxie-android-{version}'
    with zipfile.ZipFile(archive_path) as archive:
        for suffix in ('.aar', '.pom', '.module', '-sources.jar', '-javadoc.jar'):
            path = prefix + suffix
            expected = archive.read(path)
            with urllib.request.urlopen(repository + '/' + path, timeout=60) as response:
                actual = response.read()
            if hashlib.sha256(actual).digest() != hashlib.sha256(expected).digest():
                raise ValueError(f'Public artifact differs from the qualified candidate: {path}')
    with (receipt_path.parent / 'public-consumer.log').open('w') as log:
        subprocess.run([sys.executable, str(ROOT / 'scripts/test-maven-consumer.py'), '--version', version,
                        '--repository', repository], cwd=ROOT, check=True, stdout=log, stderr=subprocess.STDOUT)
    receipt['publicConsumerQualified'] = True
    save(receipt_path, receipt)
    print('Exact public artifacts and independent consumer qualified')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    build = commands.add_parser('prepare')
    build.add_argument('--version', required=True)
    build.add_argument('--public-key', type=Path, required=True)
    build.add_argument('--fingerprint', required=True)
    build.add_argument('--output-directory', type=Path, required=True)
    send = commands.add_parser('upload')
    send.add_argument('manifest', type=Path)
    recovery = commands.add_parser('recover-upload')
    recovery.add_argument('receipt', type=Path)
    recovery.add_argument('--deployment-id', required=True)
    for name in ('status', 'publish', 'qualify-public'):
        command = commands.add_parser(name)
        command.add_argument('receipt', type=Path)
    args = parser.parse_args()
    if args.command == 'prepare':
        prepare(args.version, args.public_key, args.fingerprint, args.output_directory)
    elif args.command == 'upload':
        upload(args.manifest)
    elif args.command == 'recover-upload':
        recover_upload(args.receipt, args.deployment_id)
    elif args.command == 'qualify-public':
        qualify_public(args.receipt)
    else:
        status(args.receipt, publish=args.command == 'publish')


if __name__ == '__main__':
    main()
