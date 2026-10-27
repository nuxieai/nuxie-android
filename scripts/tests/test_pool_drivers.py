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
    def test_executor_inputs_only_require_the_native_library(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            library = root / 'libnux_capi.dylib'
            library.write_bytes(b'capi')
            with patch.object(sdk, 'ROOT', root), patch.dict(os.environ, {
                'NUXIE_HOST_CAPI_LIB': str(library), 'VK_ICD_FILENAMES': '/missing/driver.json',
                'DYLD_LIBRARY_PATH': '/missing/libraries', 'NUXIE_MOLTENVK_LIBRARY': '/missing/moltenvk',
            }, clear=True):
                with sdk.host_inputs(include_graphics=False) as (files, environment):
                    self.assertEqual(files, {'.nuxie/sdk-inputs/host-runtime/libnux_capi.dylib': library.resolve()})
                    self.assertEqual(environment, {'NUXIE_HOST_CAPI_LIB': str(library.resolve())})

    def test_loader_directory_preserves_external_symlink_names_as_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);library=root/'libnux_capi.dylib';library.write_bytes(b'capi')
            target=root/'Cellar/tool/lib/library.1.dylib';target.parent.mkdir(parents=True);target.write_bytes(b'library')
            directory=root/'lib';directory.mkdir();(directory/'library.dylib').symlink_to(target)
            with patch.object(sdk,'ROOT',root),patch.dict(os.environ,{'NUXIE_HOST_CAPI_LIB':str(library),'DYLD_LIBRARY_PATH':str(directory)},clear=True):
                with sdk.host_inputs() as (files,environment):
                    staged=Path(environment['DYLD_LIBRARY_PATH'])
                    self.assertEqual((staged/'library.dylib').read_bytes(),b'library')
                    self.assertFalse((staged/'library.dylib').is_symlink())
                    self.assertIn(staged,files.values())
                self.assertFalse(staged.exists())

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
