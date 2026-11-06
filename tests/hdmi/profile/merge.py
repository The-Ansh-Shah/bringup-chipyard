#!/usr/bin/env python3
"""Merge disjoint workload sweeps only when their RTL and experiment settings match."""
import argparse,json
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('inputs',nargs='+',type=Path)
p.add_argument('--output',required=True,type=Path)
a=p.parse_args();data=[json.loads(x.read_text()) for x in a.inputs];merged=data[0]
keys=['rtl_sha256','testbench_sha256','system_hz','pixel_half_period_ns','warmup_frames','measured_frames','fifo_entries','outstanding_limit','reorder','reorder_buffer_bytes']
seen={x['name'] for x in merged['cases']}
for other in data[1:]:
 for k in keys:
  if merged[k]!=other[k]:raise SystemExit('Unmatched '+k)
 for case in other['cases']:
  if case['name'] in seen:raise SystemExit('Duplicate workload: '+case['name'])
  seen.add(case['name']);merged['cases'].append(case)
a.output.write_text(json.dumps(merged,indent=2)+'\n')
print(a.output)
