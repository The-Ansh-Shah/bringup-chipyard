#!/usr/bin/env python3
"""Read scanout status via UART-TSI without loading an ELF, writing RAM or starting a hart."""
import argparse,json,os,re,select,subprocess,time
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--chipyard',type=Path,default=Path(os.environ.get('CHIPYARD','.')))
p.add_argument('--tty',required=True)
p.add_argument('--version',choices=['original','latest'],required=True)
p.add_argument('--samples',type=int,default=1)
p.add_argument('--interval',type=float,default=1.0)
p.add_argument('--timeout',type=float,default=10)
p.add_argument('--dry-run',action='store_true')
a=p.parse_args()
if a.samples<1 or a.interval<0 or a.timeout<=0:p.error('invalid sampling parameters')
addresses=[0x10081004,0x1008100c]+([0x10081040] if a.version=='latest' else [])
command=[str(a.chipyard.resolve()/'generators/testchipip/uart_tsi/uart_tsi'),f'+tty={a.tty}','+baudrate=921600','+no_hart0_msip',*[f'+init_read=0x{x:x}' for x in addresses],'none']
if a.dry_run:
 import shlex
 print(shlex.join(command));raise SystemExit(0)
pattern=re.compile(r'Reading ([0-9a-fA-F]+) \.\.\.\s*got ([0-9a-fA-F]+)')
for sample in range(a.samples):
 start=time.monotonic();proc=subprocess.Popen(command,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
 output=b''
 try:
  while time.monotonic()-start<a.timeout:
   if select.select([proc.stdout],[],[],0.1)[0]:
    chunk=os.read(proc.stdout.fileno(),4096)
    if not chunk:break
    output+=chunk
    if len(pattern.findall(output.decode(errors='replace')))>=len(addresses):break
 finally:
  if proc.poll() is None:
   proc.terminate()
   try:proc.wait(timeout=2)
   except subprocess.TimeoutExpired:proc.kill();proc.wait()
  proc.stdout.close()
 matches=[(int(x,16),int(y,16)) for x,y in pattern.findall(output.decode(errors='replace'))]
 if [x for x,_ in matches]!=addresses:raise SystemExit('Incomplete/unexpected TSI reads:\n'+output.decode(errors='replace'))
 values=dict(matches);status=values[0x1008100c]
 row={'sample':sample+1,'host_unix_seconds':time.time(),'version':a.version,
      'underflow_this_frame':bool(values[0x10081004]&1),'busy':bool(status&1),
      'video_ready':bool(status&2),'dma_error':bool(status&4),
      'raw':{hex(k):hex(v) for k,v in matches}}
 if a.version=='latest':row['underflow_ever']=bool(values[0x10081040]&1)
 print(json.dumps(row),flush=True)
 if sample+1<a.samples:time.sleep(a.interval)
