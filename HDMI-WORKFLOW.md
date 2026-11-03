# BoraLake Rocket + HDMI workflow

Worktree `/bwrcq/C/ansh_shah/ee194-lab1-hdmi`, branch `hdmi/boralake-integration`, base `064255862d8029809c57224ecf747c5f62ad6ead`. Changes are uncommitted. Physical HDMI has not been observed and no connected board has been programmed.

## Target and source isolation

The current checkout is authoritative: BoraLake, `xc7k160tiffg676-2L`, 50 MHz oscillator metadata at F22, active-high external reset at C26, UART-TSI at 921600 baud on D23 RX / D24 TX. No FPGA package or pin assignment was changed to Nicolas's revision. Source/device checks cannot verify the installed oscillator or PCB traces; those remain physical validation items.

Initialized submodule directories and .ivy2/.sbt caches are shared with the original `ee194-lab1` checkout. Do not run submodule update or edit those source directories here. Two independent source copies in `fpga/hdmi-shell/src/main/scala` override the original BoraLake Scala classes through build.sbt. Their provenance is fpga-shells `4993661e2cc03340dea342509110133a80f23cbb`; the shared originals are untouched. `common.mk` tracks the local override directory so editing it rebuilds the generator. No other board's shell revision is used. The local Tcl entry skips only uninstantiated legacy mmcm/reset_sys/ILA IP and duplicate XCI registration; implementation stages and the original timing/DRC checks remain. Library synchronizer FFs get ASYNC_REG placement guidance, and the flow adds CDC, Gray-pointer bus-skew and hold checks. It does not downgrade violations or alter clocks solely to make a report pass.

The supplied patch did not apply to this base. Inputs remain under `/tmp/hdmi-input`, with SHA256 records in `hdmi-validation/input-sha256.txt`. Unrelated SophiaLake/C2C/I2C changes, dirty gitlinks and incompatible unused DSP24/DSP25/regulator-I2C classes were excluded. Nicolas's diff referenced fpga-shells `5cff012...-dirty` without providing its dirty changes; the current workflow no longer depends on reconstructing that revision. Original superproject edits and baremetal-ide changes were preserved. See `hdmi-validation/submodule-audit.txt` for initial revisions/dirty files.

## Hardware integration

`BuildTop` defaults to `ChipTop`, whose `BuildSystem` defaults to `DigitalTop`. `DigitalTop` mixes in `barduino_blocks.CanHaveVideoScanout`; the concrete board config enables it and the HDMI punchthrough/binder. The generic `chipyard.RocketHDMIConfig` is not the FPGA build target.

- Board config: `chipyard.fpga.boralakeold.BoraLakeRocketConfig`.
- Harness: `chipyard.fpga.boralakeold.BoraLakeHarness`.
- System: one Rocket, broadcast coherence manager, no L2, 100 MHz, UART-TSI, DDR and HDMI. No separate console UART.
- UART/LED collision is resolved by not placing LED overlays 0 and 1. D23/D24 stay assigned only to UART-TSI. Remaining LED pin assignments are unchanged. The invalid broad RX-fanout IOB request was removed; only UART TX retains its IOB request.
- The current MIG XML uses 30-bit byte addresses. The advertised memory window and MIG blackbox are now limited to the lower 1 GiB, `[0x80000000,0xc0000000)`. Physical DIMM geometry stays unchanged. Framebuffers at 0x81000000/0x81100000 map to offsets 16/17 MiB, avoiding the old hardcoded 0x100000000 address and its aliasing risk.
- The raw init_calib_complete signal now enters a ResetCatchAndSync in the system domain before releasing TLBlockDuringReset. MIG UI reset is now `!mmcm_locked || ui_clk_sync_rst`, agreeing with the island's positive reset. The previous inversion could keep ResetWrangler and the system asserted indefinitely.
- UI clock metadata is corrected to 83.333333 MHz. Generated MIG RTL confirms 83,333,333 Hz AXI/UI, 30-bit AXI, tCK=3000 ps and 4:1 PHY ratio.
- The selected memory remains MT8KTF51264HZ-1G6, 64 bits, 1.35 V. Local Vivado 2024.2's mapping database maps it to `mt8kxf51264hz-1g6.xml`. That file specifies tRFC=260 ns and CL=5 at tCK=3000 ps; generated MIG chose CL/CWL=5 while warning about the old 11/8 values. The local override explicitly uses tRFC=260, CL=5, CWL=5. These are corrections for the same chosen memory, not a package/pin-map substitution. VREF/DCI settings remain the checkout baseline unless a later documented implementation error requires correction.

Video: the nominal 50 MHz source feeds a separate two-output clock wizard requesting 25.175 and 125.875 MHz, phase 0. The IP probe achieved 25.178571/125.892857 MHz, exact 5:1, about +141.9 ppm. These are generated frequencies assuming a real 50 MHz oscillator. The harness holds system reset until both main and video PLL reset/lock bundles are ready. Scanout synchronizes reset release separately into its system and pixel domains. Serializer reset follows the pixel/CLKDIV domain; the related 5x clock is from the same PLL.

Pixel/system crossings: the old direct queue-ready/vsync/underflow paths were removed. A 64-entry asynchronous FIFO transfers `{data,word-index,frame-tag}` from DMA to pixel clock. Vsync, underflow and video-ready status use explicit three-stage ASYNC_REG flip-flops with SHREG_EXTRACT=NO. An earlier implementation report showed the resetless generic helper being packed into SRLs; that bitstream was quarantined and these protected FFs replace it. Disabling scanout resets both FIFO domains and drains any outstanding DMA response; software waits for busy=0 and reset propagation before changing base/re-enabling. DMA wraps at exactly 614400 bytes. Pixel timing never stalls; missing data displays black, sets active-video underflow, and stale tagged words are discarded so following frames recover. This replaces the unsafe practice of resetting only the dequeue side every vsync. Tags have an explicit less-than-128-frame lag bound; disable/re-enable restores synchronization after an extreme stall.

TMDS pins retained from supplied board binder:

| Signal | P | N |
|---|---|---|
| Clock | V23 | V24 |
| Data 0 | W25 | W26 |
| Data 1 | U26 | V26 |
| Data 2 | U24 | U25 |

Vivado's device database confirms all four correct P/N pairs on bank 12 for the selected part (`hdmi-validation/pin-check.log`). TMDS_33 is requested. V22 remains the supplied output-high `hdmi_tx_hpd` signal; its PCB function/direction has not been independently confirmed from a schematic. No alternative mapping is guessed.

The transmitter resource is `generators/barduino-blocks/src/main/resources/vsrc/hdmi_tx_v1_0.v`, using 7-series OSERDESE2/OBUFDS. Generated blackbox/source lists include it. Output sync polarity is now negative for the 640x480 mode. The pixel format remains the original BGR565 layout, with bit replication to full 8-bit channels.

## Software test and commands

Source: `software/hdmi-test/main.c`, startup `start.S`, linker `link.ld`. Build:

```
make -C software/hdmi-test
```

Output: `software/hdmi-test/build/hdmi-test.elf`. Standalone M-mode startup follows the existing UART-TSI BootROM convention and the baremetal-ide PT_LOAD/NOLOAD linker approach; it does not initialize SophiaLake's nonexistent console UART. Program/BSS/64 KiB stack remain below 0x80200000. Framebuffers are initialized by the CPU, not included in the ELF.

The revised actual register map is documented in `software/hdmi-test/README.md`: original vsync/underflow status at 0x00/0x04; new enable at 0x08; busy/ready/DMA-error at 0x0c; 64-bit base at 0x10, all relative to 0x10081000. Original input controller had only the two read-only status registers, no enable/base control. Hardware and this ELF must be used together.

The test fills an inactive 640x480 framebuffer (1280-byte stride, little-endian BGR565), fences, stops/drains DMA, switches base and enables. It shows white/yellow/cyan/green/magenta/red/blue/black bars, then solid red/green/blue, about two seconds each. Brief blanking during control changes is expected. DMA enters coherent SBUS ahead of the broadcast manager; Get transactions probe dirty Rocket cache lines. Fences order framebuffer stores and control writes; no unsupported cache-flush opcode or claim that fence cleans a noncoherent cache is used.

Hardware build:

```
scripts/build-boralake-hdmi.sh firrtl
scripts/build-boralake-hdmi.sh verilog
scripts/build-boralake-hdmi.sh bitstream
```

For the current CDC-fix rebuild, the exact invocation used an optional placement/routing reference:

```
HDMI_INCREMENTAL_DCP="$PWD/hdmi-validation/cdc-attempt-obj/post_route.dcp" \
 scripts/build-boralake-hdmi.sh bitstream
```

The reference's logic is not loaded as the design: the current RTL is synthesized first, and Vivado matches placement/routes for unchanged cells after optimization. The earlier bitstream in that directory is quarantined due to CDC findings and must not be programmed. The checkpoint is an ignored local build artifact, not part of integration.patch. A clean build can omit HDMI_INCREMENTAL_DCP; it runs the full place/route flow and must independently pass timing. Vivado may choose its default incremental directives when this reference is used. All sign-off checks still run.

The wrapper fixes Java PATH and uses Chisel 6.7.0 / Scala 2.13.16 / sbt 1.8.2. `env.sh` alone leaves Synopsys Java 8 first; prepend the original checkout's `.conda-env/bin` (Java 20). `nounset` is disabled only while sourcing Conda's existing activation hooks. `USE_CHISEL7` is unset. Set `CHIPYARD_ENV_ROOT` to an independent environment checkout on another host.

Exact make variables:

```
make -C fpga SUB_PROJECT=boralakeold SBT_PROJECT=chipyard_fpga \
 MODEL=BoraLakeHarness VLOG_MODEL=BoraLakeHarness \
 MODEL_PACKAGE=chipyard.fpga.boralakeold \
 CONFIG=BoraLakeRocketConfig CONFIG_PACKAGE=chipyard.fpga.boralakeold \
 GENERATOR_PACKAGE=chipyard TOP=ChipTop TB=none BOARD=boralake FPGA_BRAND=xilinx bitstream
```

Output root B: `fpga/generated-src/chipyard.fpga.boralakeold.BoraLakeHarness.BoraLakeRocketConfig/`.

- Generator assembly: `.classpath_cache/chipyard_fpga.jar`.
- FIRRTL/annotations/Chisel log: B with the same full target-name prefix and `.fir`, `.anno.json`, `.chisel.log` suffixes.
- Generated HDL, blackbox and memory models: `B/gen-collateral/`.
- Combined Vivado source list: `B/<full-target-name>.vsrcs.f`.
- Bitstream, if the build completes: `B/obj/BoraLakeHarness.bit`.
- Checkpoints: `B/obj/post_synth.dcp`, `post_opt.dcp`, `post_place.dcp`, `post_route.dcp` as emitted by Tcl.
- Vivado log: `B/vivado.log`; reports: `B/obj/report/{timing,drc,io,clocks,utilization}.txt`.

Vivado 2024.2 and both candidate device packages are installed, but only the checkout FFG part is targeted. MIG 4.2 is an IP version, not a required Vivado release. Minimal synthesis/license checkout succeeded; full build validation is recorded below. The existing report.tcl checks timing after writing the bitstream: a .bit left by a failed run is not accepted as a validated artifact. No checks/severity overrides are disabled to force bitstream creation.

Explicit programming command (NOT executed):

```
B=fpga/generated-src/chipyard.fpga.boralakeold.BoraLakeHarness.BoraLakeRocketConfig
vivado -mode batch -source scripts/program-boralake-hdmi.tcl \
 -tclargs '<HW_SERVER_HOST>:3121' '<CABLE_TARGET>' '<FPGA_DEVICE_NAME>' "$B/obj/BoraLakeHarness.bit"
```

The operator must supply the actual server/cable/device. The script selects exactly one matching Kintex-160T device. Do not run it until the bitstream passes validation and the correct physical board is selected.

Load/run ELF after programming and DDR calibration (NOT executed):

```
export LD_LIBRARY_PATH=/bwrcq/C/ansh_shah/ee194-lab1/.conda-env/riscv-tools/lib:${LD_LIBRARY_PATH:-}
generators/testchipip/uart_tsi/uart_tsi +tty='<UART_TSI_DEVICE>' +baudrate=921600 \
 +selfcheck +init_write=0x1000:0x80000000 +init_write=0x1004:0x0 software/hdmi-test/build/hdmi-test.elf
```

The host loads the ELF, applies the boot-address write, and triggers hart 0 MSIP through its existing startup sequence. No console printf is assumed. TSI-visible globals record phase, switches, status and traps. Full sequence, symbol/status-read commands, error codes and the blank-screen checklist are in `software/hdmi-test/README.md`.

Hardware checklist: connect the intended board/DDR, HDMI display, JTAG and UART-TSI; validate and program the bitstream; allow DDR calibration; load/run the ELF; expect bars → red → green → blue repeatedly. If blank, inspect implementation status, clocks/PLL reset, DDR calibration, UART selfcheck, enable/base/ready/error, and trap/phase globals. Physical testing remains required.

## Validation results

The final Vivado 2024.2 build completed successfully for xc7k160tiffg676-2L. Routed setup WNS is +0.068 ns (TNS 0); hold WHS is +0.026 ns (THS 0). DRC reports zero errors and 276 warnings (DSP pipelining, slice placement and clock buffering). The synchronizer structural assertion passes: nine discrete ASYNC_REG status FFs, zero SRLs. Both seven-bit FIFO Gray-pointer crossings pass bus-skew checks: actual 1.008 ns against 8 ns, and 0.759 ns against 30 ns.

This is not a clean CDC or complete external-I/O sign-off. report_cdc retains 17 CDC-10 critical combinational-before-synchronizer findings, one CDC-6 warning, one CDC-8 warning and 318 CDC-15 warnings. Reset logic, status decoding and library FIFO protocols still require interpretation/review; no report severity was downgraded. check_timing reports zero unconstrained internal endpoints, but two inputs and seven outputs lack external delay constraints. Timing passes under the supplied constraints, not a measured PCB timing model. HDMI pin electrical pairing was checked against the selected device; physical board wiring, oscillator, DDR calibration and display operation remain unobserved. No board was programmed.

Current bitstream: `fpga/generated-src/chipyard.fpga.boralakeold.BoraLakeHarness.BoraLakeRocketConfig/obj/BoraLakeHarness.bit`. Test ELF: `software/hdmi-test/build/hdmi-test.elf`. Build log: `hdmi-validation/bitstream.log`; timing, DRC, CDC and bus-skew reports are under the bitstream's `obj/report/`. Artifact SHA-256 values are recorded in `hdmi-validation/artifact-manifest.json`. Controller RTL simulation passed bars, pixel ordering, frame wrap, stop/drain/base switch and underflow recovery (`hdmi-validation/scanout-simulation.log`). It stubs the transmitter and does not test DDR calibration, OSERDES, TMDS pins or physical HDMI. Run after generating Verilog:

```
python tests/hdmi/run.py
```

The extended test also covers combined PLL/system reset and explicit restart, using walking 100/25.1786 MHz clock phase.

The bare-metal ELF compiled with `-Wall -Wextra -Werror`; its only link warning is the intentional machine-mode RAM LOAD segment having RWX permissions. Entry is 0x80000000, small PT_LOAD, and BSS/stack use PT_NULL. A plain Spike run reaches the first absent HDMI MMIO access, traps and exits through tohost as expected (`software-spike-trace.log`); that is a startup/trap-path check, not a passing functional HDMI simulation.

Final patch `hdmi-validation/integration.patch` includes integration source/build files, local shell copies, software test, RTL test and documentation. It excludes logs, input archives, generated HDL/ELF/bitstreams, caches and submodule changes. Changes remain uncommitted. The patch is checked with a temporary clean index at the stated base, leaving the real index untouched. For an independent clone, initialize the base submodules normally, apply the patch, build the environment, set CHIPYARD_ENV_ROOT and run the commands above. Do not copy this worktree's machine-specific dependency symlinks.
