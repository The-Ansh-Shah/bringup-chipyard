#!/usr/bin/env python3
"""Mock the UART host: validate read-only addresses, decoding and version safety."""
from pathlib import Path
import json,subprocess,tempfile
root=Path(__file__).resolve().parents[3]
with tempfile.TemporaryDirectory() as d:
 base=Path(d);host=base/'generators/testchipip/uart_tsi/uart_tsi';host.parent.mkdir(parents=True)
 host.write_text('''#!/usr/bin/env python3
import sys,time
assert '+no_hart0_msip' in sys.argv and sys.argv[-1]=='none'
assert not any('init_write' in a for a in sys.argv)
for a in sys.argv[1:]:
 if a.startswith('+init_read='):
  addr=int(a.split('=')[1],16)
  print('Reading %x ... got %x'%(addr,{0x10081004:1,0x1008100c:6,0x10081040:1}[addr]),flush=True)
time.sleep(5)
''');host.chmod(0o755)
 for version in ['original','latest']:
  cmd=['python',str(root/'software/hdmi-test/read-hdmi-status.py'),'--chipyard',str(base),'--tty','MOCK','--version',version,'--samples','2','--interval','0']
  rows=[json.loads(x) for x in subprocess.check_output(cmd,text=True).splitlines()]
  assert len(rows)==2
  for x in rows:
   assert x['underflow_this_frame'] and x['video_ready'] and x['dma_error'] and not x['busy']
   assert ('underflow_ever' in x)==(version=='latest')
   assert ('0x10081040' in x['raw'])==(version=='latest')
 print('PASS: original/latest read addresses, decoding, repeated samples; no writes, ELF load or hart start')
