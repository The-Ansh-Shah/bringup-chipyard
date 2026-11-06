#!/usr/bin/env python3
"""Profile emitted scanout RTL without editing the hardware or published release."""
import argparse, concurrent.futures, hashlib, json, os, re, shutil, subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--rtl-dir', type=Path, help='use frozen emitted RTL instead of current build')
parser.add_argument('--limit', type=int, choices=[1,2,4,8], default=1)
parser.add_argument('--reorder', action='store_true', help='return eligible bursts out of order with deterministic latency jitter')
parser.add_argument('--output', type=Path, default=ROOT/'hdmi-validation/profile-current')
parser.add_argument('--latencies', help='comma-separated supplemental latency-only sweep')
parser.add_argument('--quick', action='store_true', help='only run ideal and latency-stressed smoke cases')
parser.add_argument('--jobs', type=int, default=2, help='parallel simulations')
args = parser.parse_args()
out = args.output.resolve()
out.mkdir(parents=True, exist_ok=True)
g = ROOT/'fpga/generated-src/chipyard.fpga.boralakeold.BoraLakeHarness.BoraLakeRocketConfig/gen-collateral'
if args.rtl_dir:
    g = args.rtl_dir.resolve()
sources = {}
# MacroCompiler may put several SyncReadMem models in one .mems.v file.
for source in [*g.glob('*.sv'), *g.glob('*.v'), *g.parent.glob('*.v')]:
    for module in re.findall(r'^\s*module\s+(\w+)', source.read_text(), re.M):
        sources[module] = source

needed = set()
def visit(name):
    if name == 'hdmi_tx_v1_0' or name in needed or name not in sources:
        return
    needed.add(name)
    for child in re.findall(r'^\s+(\w+)\s+\w+\s*\(', sources[name].read_text(), re.M):
        visit(child)
visit('ClockSinkDomain_2')
if not needed:
    raise SystemExit('Generate normal BoraLake Verilog first.')
build = ROOT/'tests/hdmi/build/profile'/out.name
build.mkdir(parents=True, exist_ok=True)
verilator = os.environ.get('VERILATOR', shutil.which('verilator') or str(ROOT.parent/'ee194-lab1/.conda-env/bin/verilator'))
has_ids='auto_dma_out_a_bits_source' in sources['ClockSinkDomain_2'].read_text()
if not has_ids and args.limit != 1:
    raise SystemExit('Frozen single-source RTL only supports limit=1')
has_d_ready='auto_dma_out_d_ready' in sources['ClockSinkDomain_2'].read_text()
testbench_hash=hashlib.sha256((ROOT/'tests/hdmi/profile/profile_tb.sv').read_bytes()).hexdigest()
rtl_hashes={str(sources[n]):hashlib.sha256(sources[n].read_bytes()).hexdigest() for n in sorted(needed)}
with (out/'compile.log').open('w') as log:
    result = subprocess.run([verilator, '--binary', '--timing', '--assert', '--top-module', 'tb',
        '--Mdir', str(build), '-j', '8', *(['+define+PROFILE_HAS_SOURCE_IDS'] if has_ids else []), *(['+define+PROFILE_HAS_D_READY'] if has_d_ready else []), str(ROOT/'tests/hdmi/profile/profile_tb.sv'),
        *[str(p) for p in sorted({sources[n] for n in needed})]], stdout=log, stderr=subprocess.STDOUT)
if result.returncode:
    raise SystemExit(f'Compile failed: {out / "compile.log"}')
latencies=[int(x) for x in args.latencies.split(',')] if args.latencies else ([0,200] if args.quick else [0,25,50,75,100,110,115,120,125,140,160,200,400])
if any(x<0 for x in latencies):raise SystemExit('Latency must be nonnegative')
cases = [dict(name=f'latency-{lat}', latency=lat) for lat in latencies]
if not args.quick and not args.latencies:
    cases += [dict(name='periodic-5us', latency=50, period=5000, pause=500),
              dict(name='periodic-20us', latency=50, period=5000, pause=2000)]
    cases += [dict(name=f'random-{pct}-seed-{seed}', latency=50, random_pct=pct, seed=seed)
              for pct in [20,50] for seed in [1,42,12345]]
    cases += [dict(name='blackout-100us', latency=50, blackout_at=200000, blackout_cycles=10000),
              dict(name='blackout-1ms', latency=50, blackout_at=200000, blackout_cycles=100000)]

def run(case):
    name=case['name']
    raw=out/(name+'.json')
    with (out/(name+'.log')).open('w') as log:
        subprocess.run([str(build/'Vtb'), f'+output={raw}', f'+limit={args.limit}',f'+reorder={int(args.reorder)}',
            *[f'+{k}={v}' for k,v in case.items() if k != 'name']], check=True,
            stdout=log, stderr=subprocess.STDOUT)
    m=json.loads(raw.read_text())
    assert m['model_latency'] == case.get('latency', 0), 'Latency argument was not applied'
    assert m['model_random_pct'] == case.get('random_pct', 0), 'Random stall argument was not applied'
    assert m['model_seed'] == case.get('seed', 1), 'Seed argument was not applied'
    assert m['max_outstanding'] <= args.limit
    m['reorder'] = int(args.reorder)
    duration=m['cycles']/100_000_000
    m.update(case)
    m.update(duration_seconds=duration,
        response_MBps=m['response_bytes']/duration/1e6,
        useful_pixel_MBps=m['good_pixels']*2/duration/1e6,
        missing_pixels_per_frame=m['missing_pixels']/2,
        underflow_percent=100*m['missing_pixels']/m['active_pixels'],
        average_outstanding=m['outstanding_sum']/m['samples'],
        average_fifo_words=m['occupancy_sum']/m['samples'],
        average_first_latency_cycles=m['first_latency_sum']/max(1,m['first_responses']),
        average_completion_latency_cycles=m['completion_latency_sum']/max(1,m['completions']))
    raw.write_text(json.dumps(m,indent=2)+'\n')
    print(f"{name}: {m['response_MBps']:.3f} MB/s, missing/frame={m['missing_pixels_per_frame']:.0f}, wrong={m['wrong_pixels']}",flush=True)
    return m
with concurrent.futures.ThreadPoolExecutor(max_workers=args.jobs) as pool:
    results=list(pool.map(run,cases))
metadata={'source_commit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
          'verilator':subprocess.check_output([verilator,'--version'],text=True).strip(),
          'system_hz':100_000_000,'pixel_half_period_ns':19.858,
          'warmup_frames':1,'measured_frames':2,'fifo_entries':64,'outstanding_limit':args.limit,'reorder':int(args.reorder),'has_source_ids':has_ids,'reorder_buffer_bytes':512 if has_ids else 0,
          'rtl_sha256':rtl_hashes,
          'testbench_sha256':testbench_hash,
          'cases':results}
(out/'results.json').write_text(json.dumps(metadata,indent=2)+'\n')
lines=['# Scanout profile: simulation measurements','',
       f'100 MHz system; approximately 25.1786 MHz pixel clock; 64 FIFO words; outstanding limit {args.limit}; reorder storage {512 if has_ids else 0} bytes.',
       'One warmup frame, two measured frames. Decimal MB/s. Synthetic memory model; no DDR/coherence/serializer model.','',
       '| Workload | DMA MB/s | Useful pixel MB/s | Missing pixels/frame | First beat latency (cycles) | Avg FIFO words | Max reads |',
       '|---|---:|---:|---:|---:|---:|---:|']
for m in results:
    lines.append(f"| {m['name']} | {m['response_MBps']:.3f} | {m['useful_pixel_MBps']:.3f} | {m['missing_pixels_per_frame']:.0f} | {m['average_first_latency_cycles']:.2f} | {m['average_fifo_words']:.2f} | {m['max_outstanding']} |")
passing=[m['latency'] for m in results if m['name'].startswith('latency-') and m['missing_pixels']==0]
lines += ['',f'Largest tested fixed latency with zero missing pixels: {max(passing) if passing else "none"} model cycles.',
          'This is a sampled sweep boundary, not a proven maximum. Response bytes include stale/discarded data; useful bandwidth counts only correctly matched displayed pixels.',
          'Compare only matched experiments; account separately for the reorder buffer added by pipelining.']
(out/'REPORT.md').write_text('\n'.join(lines)+'\n')
print('Saved',out/'REPORT.md')
