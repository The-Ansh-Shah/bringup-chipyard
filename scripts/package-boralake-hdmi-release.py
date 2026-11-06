#!/usr/bin/env python3
"""Package an already built, reviewed BoraLake candidate; never programs or publishes."""
import hashlib,json,shutil,subprocess,zipfile
from pathlib import Path
root=Path(__file__).resolve().parents[1]
version='boralake-hdmi-v0.2-rc1'
out=root/'hdmi-validation/release'/version
out.mkdir(parents=True,exist_ok=True)
obj=root/'fpga/generated-src/chipyard.fpga.boralakeold.BoraLakeHarness.BoraLakeRocketConfig/obj'
build=json.loads((root/'tests/hdmi/pageflip/results/implementation.json').read_text())
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
for f,expected in build['source_sha256'].items():
    if f.endswith('.scala') or f.startswith('fpga/'):
        if sha(root/f)!=expected:raise SystemExit('Hardware source differs from built manifest: '+f)
if sha(obj/'BoraLakeHarness.bit')!='10a558803ea24182196cb8fc68c08df3785c9560ac33d7874cb6dd5dac9b33a4':
    raise SystemExit('Unexpected bitstream; review implementation before packaging')
for name in ['donut','plasma','tunnel','animated-demo','flip-stress-8','flip-stress','pageflip','hold-bars']:
    shutil.copy2(root/'software/hdmi-test/build'/f'hdmi-{name}.elf',out/f'hdmi-{name}.elf')
for f in [obj/'BoraLakeHarness.bit',root/'scripts/program-boralake-hdmi.tcl',root/'software/hdmi-test/read-flip-stats.py',root/'software/hdmi-test/read-hdmi-status.py',root/'software/hdmi-test/VALIDATION.md',root/'hdmi-validation/integration.patch',root/'hdmi-validation/patch-validation.txt']:
    shutil.copy2(f,out/f.name)
shutil.copy2(root/'software/hdmi-test/RELEASE.md',out/'README.md')
for name in ['donut','plasma','tunnel','animated']:
    src=root/'software/hdmi-test'/f'{name}-demo.README.md'
    if src.exists():shutil.copy2(src,out/src.name)
shutil.copytree(obj/'report',out/'reports',dirs_exist_ok=True)
shutil.copytree(root/'tests/hdmi/pageflip/results',out/'metrics',dirs_exist_ok=True)
for name in ['donut','plasma','tunnel']:
    for p in (root/'hdmi-validation').glob(f'{name}-preview.*'):
        shutil.copy2(p,out/p.name)
head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip()
manifest={**build,'release':version,'source_commit':head,'physical_hdmi_verified':False,
          'runtime_dma_limits':{'reset_default':4,'maximum':8,'donut_plasma_tunnel_flip_stress_8':8},
          'software_source_sha256':{str(p.relative_to(root)):sha(p) for p in (root/'software/hdmi-test').glob('*') if p.is_file()},
          'artifact_sha256':{str(p.relative_to(out)):sha(p) for p in sorted(out.rglob('*')) if p.is_file() and p.name not in ['artifact-manifest.json','SHA256SUMS']}}
(out/'artifact-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
(out/'SHA256SUMS').write_text(''.join(f'{sha(p)}  {p.relative_to(out)}\n' for p in sorted(out.rglob('*')) if p.is_file() and p.name!='SHA256SUMS'))
archive=out.parent/(version+'.zip')
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
    for p in sorted(out.rglob('*')):
        if p.is_file():z.write(p,p.relative_to(out))
subprocess.run(['sha256sum','-c','SHA256SUMS'],cwd=out,check=True)
print(archive)
