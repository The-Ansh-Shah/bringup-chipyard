#!/usr/bin/env python3
"""Export selected HDMI sources against the lab base without touching Git's real index."""
from pathlib import Path
import hashlib
import os
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
base = '064255862d8029809c57224ecf747c5f62ad6ead'
files = [
    'build.sbt', 'common.mk', 'fpga/Makefile', 'HDMI-WORKFLOW.md',
    'hdmi-validation/.gitignore',
    'scripts/build-boralake-hdmi.sh', 'scripts/program-boralake-hdmi.tcl',
    'scripts/export-hdmi-integration.py', 'scripts/check-boralake-cdc.tcl',
    'generators/barduino-blocks/build.sbt',
    'generators/chipyard/src/main/scala/DigitalTop.scala',
    'generators/chipyard/src/main/scala/config/RocketConfigs.scala',
    'generators/chipyard/src/main/scala/harness/HarnessClocks.scala',
    'generators/chipyard/src/main/scala/iobinders/IOBinders.scala',
    'generators/chipyard/src/main/scala/iobinders/Ports.scala',
]
for directory in ['fpga/src/main/scala/BoraLakeOld', 'fpga/hdmi-shell',
                  'generators/barduino-blocks/src', 'software/hdmi-test', 'tests/hdmi']:
    for path in sorted((root/directory).rglob('*')):
        rel = path.relative_to(root)
        if not path.is_file() or any(x in rel.parts for x in ['build', 'target', '__pycache__']):
            continue
        if path.suffix in ['.scala', '.v', '.sv', '.tcl', '.md', '.c', '.S', '.ld', '.py'] or path.name in ['Makefile', '.gitignore']:
            files.append(str(rel))
files = sorted(set(files))
out = root/'hdmi-validation'
out.mkdir(exist_ok=True)
patch = out/'integration.patch'
with tempfile.TemporaryDirectory(prefix='hdmi-export-') as temp:
    env = dict(os.environ, GIT_INDEX_FILE=str(Path(temp)/'index'))
    def git(*args, **kwargs):
        return subprocess.run(['git', *args], cwd=root, env=env, check=True, **kwargs)
    git('read-tree', base)
    git('add', '--', *files)
    with patch.open('wb') as stream:
        git('diff', '--cached', '--binary', '--full-index', stdout=stream)
    git('read-tree', base)
    git('apply', '--cached', '--check', str(patch))
    git('apply', '--cached', str(patch))
    for path in files:
        expected = subprocess.check_output(['git', 'hash-object', path], cwd=root, text=True).strip()
        actual = subprocess.check_output(['git', 'rev-parse', ':'+path], cwd=root, env=env, text=True).strip()
        if expected != actual:
            raise RuntimeError('Patch post-image mismatch: '+path)
(out/'integration.patch.sha256').write_text(hashlib.sha256(patch.read_bytes()).hexdigest()+'  integration.patch\n')
(out/'patch-validation.txt').write_text(f'PASS: clean-index apply at {base}\nPASS: all {len(files)} selected post-image blobs match\nActual Git index untouched.\n')
print(f'Exported and validated {len(files)} files: {patch}')
