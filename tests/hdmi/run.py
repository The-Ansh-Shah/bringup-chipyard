#!/usr/bin/env python3
"""Simulate the controller emitted by the normal FPGA build, excluding serializer."""
from pathlib import Path
import re, subprocess, os
root = Path(__file__).resolve().parents[2]
g = root/'fpga/generated-src/chipyard.fpga.boralakeold.BoraLakeHarness.BoraLakeRocketConfig/gen-collateral'
build = root/'tests/hdmi/build'
build.mkdir(exist_ok=True)
sources = {p.stem:p for p in [*g.glob('*.sv'), *g.glob('*.v')]} 
needed=set()
def visit(n):
    if n == 'hdmi_tx_v1_0' or n in needed or n not in sources: return
    needed.add(n)
    for child in re.findall(r'^\s+(\w+)\s+\w+\s*\(', sources[n].read_text(), re.M): visit(child)
visit('ClockSinkDomain_2')
if not needed: raise SystemExit('Generate FPGA Verilog before running this test')
verilator=os.environ.get('VERILATOR','/bwrcq/C/ansh_shah/ee194-lab1/.conda-env/bin/verilator')
subprocess.run([verilator,'--binary','--timing','--top-module','tb','--Mdir',str(build),'-j','8',
    str(root/'tests/hdmi/scanout_tb.sv'),*[str(sources[n]) for n in sorted(needed)]],check=True)
subprocess.run([str(build/'Vtb')],check=True)
