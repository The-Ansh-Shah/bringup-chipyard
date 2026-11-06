#!/usr/bin/env python3
"""Compare matched scanout profiles; never invent percentages for zero baselines."""
import argparse,json
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('baseline',type=Path)
p.add_argument('candidate',type=Path)
p.add_argument('--output',type=Path,required=True)
a=p.parse_args()
b=json.loads(a.baseline.read_text());c=json.loads(a.candidate.read_text())
for key in ['system_hz','pixel_half_period_ns','warmup_frames','measured_frames','fifo_entries','testbench_sha256']:
    if b[key]!=c[key]:raise SystemExit(f'Unmatched experiment setting: {key}')
bc={x['name']:x for x in b['cases']};cc={x['name']:x for x in c['cases']}
if bc.keys()!=cc.keys():raise SystemExit('Workload sets differ')
def pct(old,new,reduction=False):
    if old==0:return 'N/A (zero baseline)'
    return f'{100*((old-new) if reduction else (new-old))/old:+.2f}%'
lines=['# Scanout profiling comparison','',
       f"Queued-read limit: {b.get('outstanding_limit', 1)} → {c.get('outstanding_limit', 1)}.",
       f"FIFO payload capacity: {b['fifo_entries']*8} → {c['fifo_entries']*8} bytes; additional reorder storage: {b.get('reorder_buffer_bytes',0)} → {c.get('reorder_buffer_bytes',0)} bytes.",
       'An original-to-pipelined comparison includes extra buffering; same-design limit comparisons hold physical storage constant.','',
       '| Workload | Useful MB/s: before → after | Useful throughput change | Missing/frame: before → after | Underflow reduction |',
       '|---|---:|---:|---:|---:|']
for name,old in bc.items():
    new=cc[name]
    for k in ['latency','random_pct','seed','period','pause','blackout_at','blackout_cycles']:
        if old.get(k)!=new.get(k):raise SystemExit(f'{name}: unmatched {k}')
    if old['wrong_pixels'] or new['wrong_pixels']:raise SystemExit(f'{name}: pixel correctness failed')
    if abs(old['duration_seconds']-new['duration_seconds'])>1e-7:raise SystemExit(f'{name}: duration differs')
    lines.append(f"| {name} | {old['useful_pixel_MBps']:.3f} → {new['useful_pixel_MBps']:.3f} | "
        f"{pct(old['useful_pixel_MBps'],new['useful_pixel_MBps'])} | "
        f"{old['missing_pixels_per_frame']:.0f} → {new['missing_pixels_per_frame']:.0f} | "
        f"{pct(old['missing_pixels'],new['missing_pixels'],True)} |")
def threshold(cases):
    passing=[x['latency'] for x in cases.values() if x['name'].startswith('latency-') and x['missing_pixels']==0]
    return max(passing) if passing else None
bt,ct=threshold(bc),threshold(cc)
sampled_change=pct(bt,ct) if bt is not None and ct is not None else 'N/A'
lines+=['',f'Largest tested zero-underflow model latency: {bt} → {ct} cycles.',
        f'Change in sampled passing-latency bound: {sampled_change}.',
        'These are sampled bounds, not proven maxima. If the candidate passes the entire sweep, extend the latency range before claiming its limit.',
        'Wire-byte throughput is recorded separately in results.json and is not used as a substitute for useful displayed pixels.']
a.output.write_text('\n'.join(lines)+'\n')
print(a.output)
