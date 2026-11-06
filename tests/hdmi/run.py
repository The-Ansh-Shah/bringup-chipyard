#!/usr/bin/env python3
"""Simulate the controller emitted by the normal FPGA build, excluding serializer."""
from pathlib import Path
import re, subprocess, os
root = Path(__file__).resolve().parents[2]
g = root/'fpga/generated-src/chipyard.fpga.boralakeold.BoraLakeHarness.BoraLakeRocketConfig/gen-collateral'
build = root/'tests/hdmi/build'
build.mkdir(exist_ok=True)
sources = {}
# MacroCompiler may put several SyncReadMem models in one .mems.v file.
for source in [*g.glob('*.sv'), *g.glob('*.v'), *g.parent.glob('*.v')]:
    for module in re.findall(r'^\s*module\s+(\w+)', source.read_text(), re.M):
        sources[module] = source

needed=set()
def visit(n):
    if n == 'hdmi_tx_v1_0' or n in needed or n not in sources: return
    needed.add(n)
    for child in re.findall(r'^\s+(\w+)\s+\w+\s*\(', sources[n].read_text(), re.M): visit(child)
visit('ClockSinkDomain_2')
if not needed: raise SystemExit('Generate FPGA Verilog before running this test')
verilator=os.environ.get('VERILATOR','/bwrcq/C/ansh_shah/ee194-lab1/.conda-env/bin/verilator')
subprocess.run([verilator,'--binary','--timing','--assert','--top-module','tb','--Mdir',str(build),'-j','8',
    str(root/'tests/hdmi/scanout_tb.sv'),*[str(p) for p in sorted({sources[n] for n in needed})]],check=True)
subprocess.run([str(build/'Vtb')],check=True)
