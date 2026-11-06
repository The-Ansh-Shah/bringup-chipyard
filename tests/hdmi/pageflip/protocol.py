#!/usr/bin/env python3
"""Exercise flip cancellation, invalid submission and continuous disabled timing."""
from pathlib import Path
import os,re,shutil,subprocess
root=Path(__file__).resolve().parents[3]
g=root/'fpga/generated-src/chipyard.fpga.boralakeold.BoraLakeHarness.BoraLakeRocketConfig/gen-collateral'
out=root/'tests/hdmi/build/pageflip-protocol';out.mkdir(parents=True,exist_ok=True)
sources={}
for source in [*g.glob('*.sv'),*g.glob('*.v'),*g.parent.glob('*.v')]:
 for n in re.findall(r'^\s*module\s+(\w+)',source.read_text(),re.M):sources[n]=source
needed=set()
def visit(n):
 if n=='hdmi_tx_v1_0' or n in needed or n not in sources:return
 needed.add(n)
 for child in re.findall(r'^\s+(\w+)\s+\w+\s*\(',sources[n].read_text(),re.M):visit(child)
visit('ClockSinkDomain_2')
s=(root/'tests/hdmi/pageflip/pageflip_tb.sv').read_text()
s=s.replace(' integer swaps=8,late=0;', ' bit protocolMonitor=1;\n integer swaps=8,late=0;')
s=s.replace('if(!dut.tx.rst && dut.tx.vde)', 'if(protocolMonitor && !dut.tx.rst && dut.tx.vde)')
marker='   fd=$fopen(outputFile,"w");'
probe='''   protocolMonitor=0;
   // One completed flip has epoch 1; cancelling the next epoch-0 flip must
   // not look like a visible acknowledgement when pixel stream state resets.
   if(acknowledged!=1)$fatal(1,"run protocol test with swaps=1");
   writeReg(29'h10081020,64'h81000000);
   writeReg(29'h10081028,1);
   wait(!dut.flipPending);
   writeReg(29'h10081008,0);
   wait(count==0 && dut.state==0);
   repeat(100)@(posedge clk);
   readReg(29'h10081030,control);
   if(control!=1)$fatal(1,"cancelled flip was acknowledged as visible");
   readReg(29'h10081040,control);
   if(control!=0)$fatal(1,"intentional disable counted as underflow");
   writeReg(29'h10081020,64'h81000001);
   writeReg(29'h10081028,1);
   readReg(29'h10081028,control);
   if(control[0] || !control[2])$fatal(1,"invalid/disabled flip not rejected");
   if(dut.tx.rst || txResetPulses!=0)$fatal(1,"software disable reset transmitter");
   control={22'h0,dut.h};
   repeat(100)@(posedge px);
   if({22'h0,dut.h}==control)$fatal(1,"disabled timing stopped");
   $display("PASS: cancellation not acknowledged, invalid command rejected, disabled timing continuous, no false underflow/reset");
   $finish;
'''
assert marker in s;s=s[:s.index(marker)]+probe+'\n end\n initial begin #600000000;$fatal(1,\"protocol timeout\");end\nendmodule\n'
bench=out/'protocol_tb.sv';bench.write_text(s)
v=os.environ.get('VERILATOR',shutil.which('verilator') or str(root.parent/'ee194-lab1/.conda-env/bin/verilator'))
subprocess.run([v,'--binary','--timing','--assert','--top-module','tb','--Mdir',str(out),'-j','8','+define+PROFILE_HAS_SOURCE_IDS','+define+PAGEFLIP_RTL',str(bench),*[str(p) for p in sorted({sources[n] for n in needed})]],check=True)
subprocess.run([str(out/'Vtb'),'+swaps=1'],check=True)
