import importlib.util
import json
import os
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('sdk',Path(__file__).resolve().parents[1]/'bazel/sdk.py')
sdk=importlib.util.module_from_spec(spec);spec.loader.exec_module(sdk)


class DriverInputTests(unittest.TestCase):
    def test_icd_library_is_relocated_and_temporary_metadata_is_cleaned(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);capi=root/'libnux_capi.dylib';capi.write_bytes(b'capi')
            binary=root/'lib/libVk.dylib';binary.parent.mkdir();binary.write_bytes(b'driver')
            icd=root/'share/vulkan/icd.d/driver.json';icd.parent.mkdir(parents=True)
            for reference in ('../../../lib/libVk.dylib',str(binary)):
                icd.write_text(json.dumps({'file_format_version':'1.0.0','ICD':{'library_path':reference,'api_version':'1.3.0'}}))
                with patch.object(sdk,'ROOT',root),patch.dict(os.environ,{'NUXIE_HOST_CAPI_LIB':str(capi),'VK_ICD_FILENAMES':str(icd)},clear=True):
                    with sdk.host_inputs() as (files,environment):
                        self.assertEqual(files['.nuxie/sdk-inputs/host-runtime/libnux_capi.dylib'],capi.resolve())
                        descriptor=Path(environment['VK_ICD_FILENAMES'])
                        data=json.loads(descriptor.read_text())
                        self.assertEqual(data['ICD']['library_path'],'./libVk.dylib')
                        stage=root/'worker';stage.mkdir(exist_ok=True)
                        for name,file in files.items():
                            target=stage/name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(file,target)
                        staged=stage/'.nuxie/sdk-inputs/vulkan-0/icd.json'
                        self.assertEqual((staged.parent/data['ICD']['library_path']).read_bytes(),b'driver')
                    self.assertFalse(descriptor.exists())


if __name__=='__main__':unittest.main()
