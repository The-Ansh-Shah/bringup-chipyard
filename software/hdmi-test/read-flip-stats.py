#!/usr/bin/env python3
"""Read a consistent cadence snapshot; requires sole access to the UART-TSI port."""
import argparse,json,os,re,select,shlex,shutil,subprocess,time
from pathlib import Path

FIELDS=['hdmi_frames_switched','hdmi_elapsed_cycles','hdmi_interval_cycles_last','hdmi_interval_cycles_max','hdmi_status']
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--elf',type=Path,default=Path(__file__).parent/'build/hdmi-flip-stress.elf')
p.add_argument('--chipyard',type=Path,default=Path(os.environ.get('CHIPYARD','.')))
p.add_argument('--nm',default=shutil.which('riscv64-unknown-elf-nm') or '/bwrcq/C/ansh_shah/ee194-lab1/.conda-env/riscv-tools/bin/riscv64-unknown-elf-nm')
p.add_argument('--tty',required=True)
p.add_argument('--dry-run',action='store_true',help='print read-only command without accessing hardware')
p.add_argument('--timeout',type=float,default=10)
a=p.parse_args()
symbols={}
for line in subprocess.check_output([a.nm,'-n',str(a.elf)],text=True).splitlines():
 parts=line.split()
 if len(parts)==3:symbols[parts[2]]=int(parts[0],16)
for name in ['hdmi_stats_seq',*FIELDS]:
 if name not in symbols:raise SystemExit(f'Missing {name}; use matching flip-stress ELF')
seq=symbols['hdmi_stats_seq']
addresses=[seq,*[symbols[n]+offset for n in FIELDS for offset in (0,4)],seq]
command=[str(a.chipyard.resolve()/'generators/testchipip/uart_tsi/uart_tsi'),f'+tty={a.tty}','+baudrate=921600','+no_hart0_msip',*[f'+init_read=0x{x:x}' for x in addresses],'none']
if a.dry_run:
 print(shlex.join(command));raise SystemExit(0)
pattern=re.compile(r'Reading ([0-9a-fA-F]+) \.\.\.\s*got ([0-9a-fA-F]+)')
for attempt in range(5):
 process=subprocess.Popen(command,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
 output=b'';deadline=time.monotonic()+a.timeout
 try:
  while time.monotonic()<deadline:
   if select.select([process.stdout],[],[],0.1)[0]:
    chunk=os.read(process.stdout.fileno(),4096)
    if not chunk:break
    output+=chunk
    if len(pattern.findall(output.decode(errors='replace')))>=len(addresses):break
 finally:
  if process.poll() is None:
   process.terminate()
   try:process.wait(timeout=2)
   except subprocess.TimeoutExpired:process.kill();process.wait()
  process.stdout.close()
 matches=[(int(x,16),int(y,16)) for x,y in pattern.findall(output.decode(errors='replace'))]
 if [x for x,_ in matches]!=addresses:
  raise SystemExit('Incomplete/unexpected TSI reads. Check port/environment/connection.\n'+output.decode(errors='replace'))
 values=[y for _,y in matches]
 if values[0]!=values[-1] or values[0]&1:continue
 result={n:values[1+2*i] | (values[2+2*i]<<32) for i,n in enumerate(FIELDS)}
 frames=result['hdmi_frames_switched'];elapsed=result['hdmi_elapsed_cycles']
 result['average_presented_fps']=(frames-1)*100_000_000/elapsed if frames>=2 and elapsed else None
 result['sequence']=values[0]
 print(json.dumps(result,indent=2));break
else:raise SystemExit('No consistent snapshot in five attempts; try again.')
