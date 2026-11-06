#!/usr/bin/env python3
"""Compare controller-only out-of-context synthesis area; this is not board sign-off."""
from pathlib import Path
import argparse,re,subprocess
root=Path(__file__).resolve().parents[3]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--rtl-dir',type=Path,required=True)
p.add_argument('--output',type=Path,required=True)
p.add_argument('--vivado',default='/tools/xilinx/Vivado/2024.2/bin/vivado')
a=p.parse_args();g=a.rtl_dir.resolve();out=a.output.resolve();out.mkdir(parents=True,exist_ok=True)
sources = {}
# MacroCompiler may put several SyncReadMem models in one .mems.v file.
for source in [*g.glob('*.sv'), *g.glob('*.v'), *g.parent.glob('*.v')]:
    for module in re.findall(r'^\s*module\s+(\w+)', source.read_text(), re.M):
        sources[module] = source

tx_source=root/'generators/barduino-blocks/src/main/resources/vsrc/hdmi_tx_v1_0.v'
for module in re.findall(r'^\s*module\s+(\w+)',tx_source.read_text(),re.M):sources[module]=tx_source
needed=set()
def visit(n):
 if n in needed or n not in sources:return
 needed.add(n)
 for child in re.findall(r'^\s+(\w+)\s+\w+\s*\(',sources[n].read_text(),re.M):visit(child)
visit('ClockSinkDomain_2')
if not needed:raise SystemExit('Missing emitted controller')
script=out/'synth.tcl'
script.write_text('\n'.join([
 'set_param general.maxThreads 4',
 *['read_verilog -sv {'+str(p)+'}' for p in sorted({sources[n] for n in needed})],
 'synth_design -top ClockSinkDomain_2 -part xc7k160tiffg676-2L -mode out_of_context -flatten_hierarchy rebuilt',
 'report_utilization -hierarchical -file {'+str(out/'utilization.txt')+'}',
 'write_checkpoint -force {'+str(out/'post_synth.dcp')+'}',
 'exit'])+'\n')
with (out/'synthesis.log').open('w') as log:
 subprocess.run([a.vivado,'-mode','batch','-source',str(script),'-log',str(out/'vivado.log'),'-journal',str(out/'vivado.jou')],cwd=out,stdout=log,stderr=subprocess.STDOUT,check=True)
print(out/'utilization.txt')
