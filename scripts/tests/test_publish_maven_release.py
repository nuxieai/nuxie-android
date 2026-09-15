import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import hashlib
import io
import zipfile

spec = importlib.util.spec_from_file_location('publisher', Path(__file__).resolve().parents[1] / 'publish-maven-release.py')
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)
DEPLOYMENT = '28570f16-da32-4c14-bd2e-c1acc0782365'


class CentralDeploymentTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.manifest = self.directory / 'release.json'
        self.receipt = self.directory / 'deployment.json'
        self.data = b'qualified bundle bytes'
        (self.directory / 'bundle.zip').write_bytes(self.data)
        self.hash = hashlib.sha256(self.data).hexdigest()
        self.manifest.write_text(json.dumps({'version': '0.1.0', 'sourceCommit': 'a' * 40, 'bundleSha256': self.hash}))
        self.environment = patch.dict(os.environ, {'NUXIE_CENTRAL_USERNAME': 'test-user', 'NUXIE_CENTRAL_PASSWORD': 'test-password'})
        self.environment.start()
        self.addCleanup(self.environment.stop)
        self.source = patch.object(publisher, 'tagged_source', return_value='a' * 40)
        self.source.start()
        self.addCleanup(self.source.stop)

    def uploaded(self):
        with patch.object(publisher, 'request', return_value=DEPLOYMENT.encode()):
            publisher.upload(self.manifest)
        return json.loads(self.receipt.read_text())

    def response(self, state):
        receipt = json.loads(self.receipt.read_text())
        return json.dumps({'deploymentId': DEPLOYMENT, 'deploymentName': receipt['deploymentName'], 'deploymentState': state}).encode()

    def test_upload_is_user_managed_and_persists_identity(self):
        with patch.object(publisher, 'request', return_value=DEPLOYMENT.encode()) as request:
            publisher.upload(self.manifest)
        endpoint, body, content_type = request.call_args.args
        self.assertIn('publishingType=USER_MANAGED', endpoint)
        self.assertIn(b'name="bundle"; filename="bundle.zip"', body)
        self.assertIn(self.data, body)
        self.assertTrue(content_type.startswith('multipart/form-data; boundary='))
        receipt = json.loads(self.receipt.read_text())
        self.assertEqual(receipt['deploymentId'], DEPLOYMENT)
        self.assertEqual(receipt['bundleSha256'], self.hash)
        self.assertEqual(receipt['state'], 'UPLOADED')

    def test_uncertain_upload_cannot_be_retried(self):
        with patch.object(publisher, 'request', side_effect=TimeoutError) as request:
            with self.assertRaises(TimeoutError):
                publisher.upload(self.manifest)
            with self.assertRaises(FileExistsError):
                publisher.upload(self.manifest)
            self.assertEqual(request.call_count, 1)
        self.assertEqual(json.loads(self.receipt.read_text())['state'], 'UPLOAD_STARTED')

    def test_recovery_binds_only_the_original_named_attempt(self):
        with patch.object(publisher, 'request', side_effect=TimeoutError):
            with self.assertRaises(TimeoutError):
                publisher.upload(self.manifest)
        response = self.response('VALIDATED')
        wrong = json.loads(response)
        wrong['deploymentName'] = 'another release'
        with patch.object(publisher, 'request', return_value=json.dumps(wrong).encode()):
            with self.assertRaises(ValueError):
                publisher.recover_upload(self.receipt, DEPLOYMENT)
        self.assertNotIn('deploymentId', json.loads(self.receipt.read_text()))
        with patch.object(publisher, 'request', return_value=response):
            publisher.recover_upload(self.receipt, DEPLOYMENT)
        self.assertEqual(json.loads(self.receipt.read_text())['deploymentId'], DEPLOYMENT)

    def test_changed_bundle_never_contacts_central(self):
        (self.directory / 'bundle.zip').write_bytes(b'changed')
        with patch.object(publisher, 'request') as request:
            with self.assertRaisesRegex(ValueError, 'changed'):
                publisher.upload(self.manifest)
            request.assert_not_called()
        self.assertFalse(self.receipt.exists())

    def test_missing_credentials_do_not_start_upload(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(publisher, 'request') as request:
            with self.assertRaises(ValueError):
                publisher.upload(self.manifest)
            request.assert_not_called()
        self.assertFalse(self.receipt.exists())

    def test_publish_requires_validated_remote_state(self):
        self.uploaded()
        with patch.object(publisher, 'request', side_effect=[self.response('VALIDATED'), b'']) as request:
            publisher.status(self.receipt, publish=True)
        self.assertEqual(request.call_args.args[0], 'deployment/' + DEPLOYMENT)
        self.assertEqual(json.loads(self.receipt.read_text())['state'], 'PUBLISH_REQUESTED')

    def test_pending_and_failed_deployments_are_not_published(self):
        self.uploaded()
        for state in ['PENDING', 'VALIDATING', 'FAILED']:
            with self.subTest(state=state), patch.object(publisher, 'request', return_value=self.response(state)) as request:
                with self.assertRaises(ValueError):
                    publisher.status(self.receipt, publish=True)
                self.assertEqual(request.call_count, 1)

    def test_published_deployment_is_not_published_again(self):
        self.uploaded()
        with patch.object(publisher, 'request', return_value=self.response('PUBLISHED')) as request:
            publisher.status(self.receipt, publish=True)
        self.assertEqual(request.call_count, 1)

    def test_wrong_deployment_identity_is_rejected(self):
        self.uploaded()
        response = json.loads(self.response('VALIDATED'))
        response['deploymentName'] = 'different candidate'
        with patch.object(publisher, 'request', return_value=json.dumps(response).encode()) as request:
            with self.assertRaises(ValueError):
                publisher.status(self.receipt, publish=True)
            self.assertEqual(request.call_count, 1)

    def public_candidate(self):
        artifacts = {f'ai/nuxie/nuxie-android/0.1.0/nuxie-android-0.1.0{suffix}': suffix.encode()
                     for suffix in ('.aar', '.pom', '.module', '-sources.jar', '-javadoc.jar')}
        archive = self.directory / 'bundle.zip'
        with zipfile.ZipFile(archive, 'w') as output:
            for name, data in artifacts.items():
                output.writestr(name, data)
        manifest = json.loads(self.manifest.read_text())
        manifest['bundleSha256'] = hashlib.sha256(archive.read_bytes()).hexdigest()
        self.manifest.write_text(json.dumps(manifest))
        self.uploaded()
        return artifacts

    def test_public_qualification_compares_every_artifact_before_consumer(self):
        artifacts = self.public_candidate()
        urls = []
        def download(url, timeout):
            urls.append(url)
            self.assertEqual(timeout, 60)
            prefix = 'https://repo.maven.apache.org/maven2/'
            self.assertTrue(url.startswith(prefix))
            return io.BytesIO(artifacts[url.removeprefix(prefix)])
        with patch.object(publisher, 'request', return_value=self.response('PUBLISHED')), \
             patch.object(publisher.urllib.request, 'urlopen', side_effect=download), \
             patch.object(publisher.subprocess, 'run') as consumer:
            publisher.qualify_public(self.receipt)
        self.assertEqual(len(urls), 5)
        self.assertIn('https://repo.maven.apache.org/maven2', consumer.call_args.args[0])
        self.assertTrue(json.loads(self.receipt.read_text())['publicConsumerQualified'])

    def test_changed_public_artifact_blocks_consumer_qualification(self):
        self.public_candidate()
        with patch.object(publisher, 'request', return_value=self.response('PUBLISHED')), \
             patch.object(publisher.urllib.request, 'urlopen', return_value=io.BytesIO(b'different artifact')), \
             patch.object(publisher.subprocess, 'run') as consumer:
            with self.assertRaisesRegex(ValueError, 'differs'):
                publisher.qualify_public(self.receipt)
            consumer.assert_not_called()
        self.assertNotIn('publicConsumerQualified', json.loads(self.receipt.read_text()))

    def test_validation_is_not_publication(self):
        self.uploaded()
        with patch.object(publisher, 'request', return_value=self.response('VALIDATED')), \
             patch.object(publisher.urllib.request, 'urlopen') as download:
            with self.assertRaisesRegex(ValueError, 'PUBLISHED'):
                publisher.qualify_public(self.receipt)
            download.assert_not_called()

    def test_credential_header_and_no_redirect_handler(self):
        class Response:
            def __enter__(self): return self
            def __exit__(self, *args): pass
            def read(self): return b'result'
        with patch.object(publisher.urllib.request, 'build_opener') as create:
            create.return_value.open.return_value = Response()
            self.assertEqual(publisher.request('status?id=' + DEPLOYMENT), b'result')
            request = create.return_value.open.call_args.args[0]
            self.assertEqual(request.full_url, publisher.CENTRAL + 'status?id=' + DEPLOYMENT)
            self.assertEqual(request.method, 'POST')
            self.assertEqual(request.get_header('Authorization'), 'Bearer dGVzdC11c2VyOnRlc3QtcGFzc3dvcmQ=')
            self.assertIs(create.call_args.args[0], publisher.NoRedirects)
        with self.assertRaises(ValueError):
            publisher.NoRedirects().redirect_request(None, None, 302, '', {}, 'https://elsewhere.invalid')


if __name__ == '__main__':
    unittest.main()
