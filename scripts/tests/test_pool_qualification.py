import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[2]


class CompleteQualificationRoutingTests(unittest.TestCase):
    def test_consumer_and_device_assertions_run_on_the_selected_worker(self):
        with tempfile.TemporaryDirectory() as temporary:
            home=Path(temporary);helper=home/'.local/share/nuxie-homelab/sdk-task.py';helper.parent.mkdir(parents=True)
            helper.write_text("import json,os\ndef route(root,args,**kwargs):\n open(os.environ['RECORDED'],'w').write(json.dumps({'args':args,'tools':kwargs['requirements']}))\n return 17\n")
            environment=dict(os.environ,HOME=str(home),RECORDED=str(home/'record.json'),NUXIE_HOMELAB='1',NUXIE_EXECUTION='pool',_NUXIE_POOL_NESTED='0')
            for script,arguments,device in [('test-maven-consumer.py',['--version','0.2.0'],None),
                    ('test-startup-process-death.py',['--device','emulator-5558'],'emulator-5558')]:
                result=subprocess.run([sys.executable,str(ROOT/'scripts'/script),*arguments],env=environment,capture_output=True,text=True)
                self.assertEqual(result.returncode,17,result.stderr)
                recorded=json.loads((home/'record.json').read_text())
                self.assertEqual(recorded['args'][0],'script-'+Path(script).stem)
                if device:self.assertIn('android-device@'+device,recorded['tools'])

    def test_root_gradle_project_keeps_canonical_paths(self):
        import importlib.util
        from unittest.mock import patch
        spec=importlib.util.spec_from_file_location('sdk_pool',ROOT/'scripts/pool.py')
        pool=importlib.util.module_from_spec(spec);spec.loader.exec_module(pool)
        with tempfile.TemporaryDirectory() as temporary:
            home=Path(temporary);helper=home/'.local/share/nuxie-homelab/sdk-task.py';helper.parent.mkdir(parents=True)
            helper.write_text("def route(root,args,**kwargs):\n assert all(isinstance(name,str) for name in kwargs['input_files'])\n assert not any(name.startswith('./') for name in kwargs['outputs'])\n assert not any(a != b and a.startswith(b + '/') for a in kwargs['outputs'] for b in kwargs['outputs'])\n return 17\n")
            with patch.object(pool.Path,'home',return_value=home),patch.dict(os.environ,{'NUXIE_HOMELAB':'1','NUXIE_EXECUTION':'pool','_NUXIE_POOL_NESTED':'0'}):
                self.assertEqual(pool.dispatch('gradle',['-p',str(ROOT),'test']),17)


if __name__=='__main__':unittest.main()
