#!/usr/bin/env python3
"""Measure presentation correctness/cadence on actual generated scanout RTL."""
from pathlib import Path
import argparse,concurrent.futures,hashlib,json,os,re,shutil,subprocess
root=Path(__file__).resolve().parents[3]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--rtl-dir',type=Path,default=root/'fpga/generated-src/chipyard.fpga.boralakeold.BoraLakeHarness.BoraLakeRocketConfig/gen-collateral')
p.add_argument('--output',type=Path,required=True)
p.add_argument('--legacy',action='store_true')
p.add_argument('--quick',action='store_true')
p.add_argument('--jobs',type=int,default=3)
a=p.parse_args();g=a.rtl_dir.resolve();out=a.output.resolve();out.mkdir(parents=True,exist_ok=True)
sources = {}
# MacroCompiler may put several SyncReadMem models in one .mems.v file.
for source in [*g.glob('*.sv'), *g.glob('*.v'), *g.parent.glob('*.v')]:
    for module in re.findall(r'^\s*module\s+(\w+)', source.read_text(), re.M):
        sources[module] = source
needed=set()

def visit(n):
 if n=='hdmi_tx_v1_0' or n in needed or n not in sources:return
 needed.add(n)
 for child in re.findall(r'^\s+(\w+)\s+\w+\s*\(',sources[n].read_text(),re.M):visit(child)
visit('ClockSinkDomain_2')
if not needed:raise SystemExit('Missing emitted controller')
has_flip='flipsCompleted' in sources['ClockSinkDomain_2'].read_text()
if has_flip==a.legacy:raise SystemExit('Select --legacy only for old RTL')
bench=root/'tests/hdmi/pageflip/pageflip_tb.sv';bench_hash=hashlib.sha256(bench.read_bytes()).hexdigest();build=root/'tests/hdmi/build/pageflip'/out.name;build.mkdir(parents=True,exist_ok=True)
flags=['+define+PROFILE_HAS_SOURCE_IDS']
if has_flip:flags+=['+define+PAGEFLIP_RTL']
if 'auto_dma_out_d_ready' in sources['ClockSinkDomain_2'].read_text():flags+=['+define+PROFILE_HAS_D_READY']
verilator=os.environ.get('VERILATOR',shutil.which('verilator') or str(root.parent/'ee194-lab1/.conda-env/bin/verilator'))
with (out/'compile.log').open('w') as log:
 r=subprocess.run([verilator,'--binary','--timing','--assert','--top-module','tb','--Mdir',str(build),'-j','8',*flags,str(bench),*[str(p) for p in sorted({sources[n] for n in needed})]],stdout=log,stderr=subprocess.STDOUT)
if r.returncode:raise SystemExit(f'Compile failed: {out}/compile.log')
cases=[dict(name='ideal',latency=0)]
if not a.quick and not a.legacy:cases += [dict(name='latency600',latency=600),dict(name='reordered',latency=200,random_pct=20,reorder=1),dict(name='late-submission',latency=0,late=1)]
def run(case):
 raw=out/(case['name']+'.json')
 with (out/(case['name']+'.log')).open('w') as log:
  r=subprocess.run([str(build/'Vtb'),f'+output={raw}',*[f'+{k}={v}' for k,v in case.items() if k!='name']],stdout=log,stderr=subprocess.STDOUT)
 if r.returncode:raise RuntimeError(f'{case["name"]} failed: {out}/{case["name"]}.log')
 d=json.loads(raw.read_text());d.update(case)
 d['acknowledged_updates_per_second']=(d['flips']-1)*1e8/(d['last_ack_cycle']-d['first_ack_cycle'])
 d['frame_rate_hz']=1e9/(39.716*d['max_frame_pixel_cycles'])
 raw.write_text(json.dumps(d,indent=2)+'\n')
 print(case['name'],d,flush=True);return d
with concurrent.futures.ThreadPoolExecutor(max_workers=a.jobs) as pool:results=list(pool.map(run,cases))
meta={'legacy':a.legacy,'pixel_half_period_ns':19.858,'system_hz':100000000,'testbench_sha256':bench_hash,'rtl_sha256':{str(sources[n]):hashlib.sha256(sources[n].read_bytes()).hexdigest() for n in sorted(needed)},'cases':results}
(out/'results.json').write_text(json.dumps(meta,indent=2)+'\n')
