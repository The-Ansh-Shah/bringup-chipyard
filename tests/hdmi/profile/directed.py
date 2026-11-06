#!/usr/bin/env python3
"""Exercise multiple in-flight reads, stop/drain, restart, reset and error latching."""
from pathlib import Path
import os,re,shutil,subprocess
root=Path(__file__).resolve().parents[3]
g=root/'fpga/generated-src/chipyard.fpga.boralakeold.BoraLakeHarness.BoraLakeRocketConfig/gen-collateral'
out=root/'tests/hdmi/build/directed';out.mkdir(parents=True,exist_ok=True)
sources = {}
# MacroCompiler may put several SyncReadMem models in one .mems.v file.
for source in [*g.glob('*.sv'), *g.glob('*.v'), *g.parent.glob('*.v')]:
    for module in re.findall(r'^\s*module\s+(\w+)', source.read_text(), re.M):
        sources[module] = source
needed=set()

def visit(n):
 if n=='hdmi_tx_v1_0' or n in needed or n not in sources:return
 needed.add(n)
 for child in re.findall(r'^\s+(\w+)\s+\w+\s*\(',sources[n].read_text(),re.M):visit(child)
visit('ClockSinkDomain_2')
tb=(root/'tests/hdmi/profile/profile_tb.sv').read_text()
tb=tb.replace('if(measuring && !finished && dut.tx.vde)', 'if(measuring && !finished && dut.tx.vde && dut.running)')
tb=tb.replace(".auto_dma_out_d_bits_corrupt(1'b0)",".auto_dma_out_d_bits_corrupt(1'b1)")
marker='   fd=$fopen(outputFile,"w");'
extra='''   if(!dut.dmaError)$fatal(1,"corrupt response did not latch DMA error");
   // With long response latency, there must really be multiple in-flight IDs.
   wait(count>=2);
   writeReg(29'h10081008,0);
   wait(count==0 && dut.state==0);
   repeat(100)@(posedge clk);
   if(dmaAV || dut.dmaError)$fatal(1,"disable failed to drain/clear error");
   // Restart the same framebuffer at frame/word zero and verify an entire frame.
   requestSequence=0;pushed=0;popped=0;
   activePixels=0;missingPixels=0;goodPixels=0;
   writeReg(29'h10081008,1);
   measuring=1;finished=0;
   wait(dut.frame==1);
   @(negedge clk);finished=1;
   if(activePixels!=307200 || missingPixels!=0)$fatal(1,"restart frame incorrect");
   // Global reset flushes queued responses and requires a fresh software start.
   @(negedge clk);reset=1;videoReset=1;measuring=0;
   repeat(100)@(posedge clk);
   @(negedge clk);reset=0;videoReset=0;
   repeat(100)@(posedge clk);
   if(dmaAV || count!=0)$fatal(1,"reset restarted DMA without enable");
   requestSequence=0;pushed=0;popped=0;activePixels=0;missingPixels=0;
   writeReg(29'h10081018,8);
   writeReg(29'h10081008,1);measuring=1;finished=0;
   wait(dut.frame==1);
   @(negedge clk);finished=1;
   if(activePixels!=307200 || missingPixels!=0)$fatal(1,"post-reset frame incorrect");
   $display("PASS: multi-ID stop/drain, restart, reset and corrupt-response error latch");
   $finish;
'''
if marker not in tb:raise SystemExit('Testbench insertion point changed')
tb=tb[:tb.index(marker)]+extra+"""
 end
 initial begin #500000000; $fatal(1,"directed timeout");end
endmodule
"""
bench=out/'directed_tb.sv';bench.write_text(tb)
verilator=os.environ.get('VERILATOR',shutil.which('verilator') or str(root.parent/'ee194-lab1/.conda-env/bin/verilator'))
subprocess.run([verilator,'--binary','--timing','--assert','--top-module','tb','--Mdir',str(out),'-j','8','+define+PROFILE_HAS_SOURCE_IDS',str(bench),*[str(p) for p in sorted({sources[n] for n in needed})]],check=True)
subprocess.run([str(out/'Vtb'),'+latency=400','+limit=8','+reorder=1'],check=True)
