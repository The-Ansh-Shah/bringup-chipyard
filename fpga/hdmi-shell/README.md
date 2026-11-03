# Local BoraLake shell overrides

BoraLakeShell.scala and BoraLakeDDRShell.scala were copied from fpga-shells commit 4993661e2cc03340dea342509110133a80f23cbb. build.sbt excludes the original two files and compiles these independent files instead. common.mk tracks this directory for rebuilds. Shared submodule sources are never edited.

The current checkout part xc7k160tiffg676-2L, pin map, memory device geometry, VREF/DCI settings remain the baseline. Nicolas's shell revision is not used.

Intentional changes: restrict advertised DDR aperture to 1 GiB, matching the existing 30-bit MIG XML address interface and selecting the blackbox's 30-bit case; correct the UI reset polarity to agree with the MIG island; describe the DDR UI clock as 83.333 MHz from 3000 ps / PHY ratio 4. MIG timings are corrected using the installed Vivado 2024.2 part database, data/mem_tlib/ddr3_sdram/sodimms/mt8kxf51264hz-1g6.xml: tRFC=260 ns, CL=5 and CWL=5 at tCK=3000 ps. The old source specified 110 ns / 11 / 8; generation warned and silently selected CL/CWL=5. This is a correction for the same selected memory/FPGA, not a change to Nicolas's board revision. The generated IP confirms 30-bit AXI addressing and an 83.333 MHz UI clock. The harness reserves D23/D24 for UART-TSI by not placing LED overlays 0 and 1; no pins are relocated.
