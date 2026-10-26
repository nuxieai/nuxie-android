import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

spec=importlib.util.spec_from_file_location('sdk',Path(__file__).resolve().parents[1]/'bazel/sdk.py')
sdk=importlib.util.module_from_spec(spec);spec.loader.exec_module(sdk)


class PoolPublicationTests(unittest.TestCase):
    def test_signed_publication_preserves_other_versions_and_rebuilds_receipt(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);incoming=root/'incoming';output=root/'maven'
            coordinate=Path('ai/nuxie/nuxie-android/0.2.0');directory=incoming/coordinate;directory.mkdir(parents=True)
            records=[]
            for suffix in ('.aar','-sources.jar','-javadoc.jar','.pom','.module'):
                file=directory/('nuxie-android-0.2.0'+suffix);file.write_bytes(b'product')
                records.append(dict(path=file.relative_to(incoming).as_posix(),size=7,sha256=hashlib.sha256(b'product').hexdigest()))
            (incoming/'licenses').mkdir();(incoming/'licenses/LICENSE').write_text('license')
            receipt=dict(sourceRevision='a'*40,sourceDirty=False,maven=dict(version='0.2.0'),runtime=dict(mode='published'),artifacts=records)
            (incoming/'sdk-artifacts.json').write_text(json.dumps(receipt))
            old=output/'ai/nuxie/nuxie-android/0.1.0/keep';old.parent.mkdir(parents=True);old.write_bytes(b'older')
            def sign(directory,products):
                for file in products:file.with_name(file.name+'.asc').write_bytes(b'signature')
            args=SimpleNamespace(output=output,maven_version='0.2.0',sign=True)
            with patch.object(sdk,'source_identity',return_value=('a'*40,False)),patch.object(sdk,'sign',side_effect=sign):
                sdk.publish_prepared(args,incoming,('a'*40,False))
            self.assertEqual(old.read_bytes(),b'older')
            self.assertTrue((output/coordinate/'nuxie-android-0.2.0.aar.asc.sha256').is_file())
            delivered=json.loads((output/'sdk-artifacts.json').read_text())
            for entry in delivered['artifacts']:
                self.assertEqual(sdk.sha256(output/entry['path']),entry['sha256'])

    def test_remote_prepare_excludes_signing_flag_and_uses_private_incoming_output(self):
        with tempfile.TemporaryDirectory() as temporary:
            dispatch=Mock(return_value=9)
            args=SimpleNamespace(command='prepare',maven_version='0.2.0',sign=True,output=Path(temporary)/'maven')
            with patch.object(sdk,'ROOT',Path(temporary)),patch.object(sdk,'source_identity',return_value=('a'*40,False)), \
                    patch.dict(sys.modules,{'pool':SimpleNamespace(dispatch=dispatch)}),patch.dict(os.environ,{'_NUXIE_POOL_NESTED':'0','NUXIE_CI_RUNNER_POLICY_VERSION':'0','NUXIE_HOMELAB':'1','NUXIE_EXECUTION':'pool'}):
                self.assertEqual(sdk.pool_dispatch(args),9)
            self.assertNotIn('--sign',dispatch.call_args.args[1])
            self.assertNotEqual(dispatch.call_args.kwargs['output'],args.output)

    def test_readonly_cas_product_can_be_published_repeatedly(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);source=root/'cas';source.write_bytes(b'output');source.chmod(0o444)
            destination=root/'delivered'
            sdk.publish_file(source,destination);sdk.publish_file(source,destination)
            self.assertEqual(destination.read_bytes(),b'output')
            self.assertEqual(destination.stat().st_mode&0o777,0o644)
            self.assertEqual(source.stat().st_mode&0o777,0o444)


if __name__=='__main__':unittest.main()
