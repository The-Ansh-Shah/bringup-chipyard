# BoraLake HDMI v0.2-rc1: pipelined DMA + continuous page flips

Hardware validation candidate for **xc7k160tiffg676-2L** (FFG676). Do not use on a different board/package. Built with Vivado 2024.2. No physical display validation has been performed for this candidate.

The DMA supports **eight outstanding 64-byte reads**, with a reset default limit of four. The new donut, plasma, tunnel and `hdmi-flip-stress-8.elf` programs select eight slots while scanout is stopped. Responses are buffered by source ID and retired in framebuffer order. Normal image updates use frame-boundary page flips without resetting HDMI timing.

## Demos

| ELF | Workload |
|---|---|
| hdmi-donut.elf | CPU-rendered rotating, lit 3D ASCII torus with depth buffering |
| hdmi-plasma.elf | Animated procedural color fields |
| hdmi-tunnel.elf | Scrolling neon perspective tunnel |
| hdmi-animated-demo.elf | Bouncing/blinking smiley with dirty-rectangle updates |
| hdmi-flip-stress-8.elf | Maximum-rate page flips between pre-rendered bars/red, eight DMA slots |
| hdmi-flip-stress.elf | Same presentation workload, four DMA slots |
| hdmi-pageflip.elf | Colors/bars changing about every two seconds, continuous timing |
| hdmi-hold-bars.elf | Static diagnostic |

HDMI timing is 640x480 at approximately **59.95 Hz** regardless of software render speed. Renderers target a new image each refresh, but **60 FPS rendering is not established**. When software is late, hardware repeats the old image; no benchmark here proves physical display correctness. Fast alternating stress patterns may flicker; start with the donut or static bars.

## Connect, program and run

Connect board power, JTAG, the UART-TSI USB connection and an HDMI display. Select the display input. Confirm the FPGA package. Set these paths for the recipient's machine; stop other UART-TSI hosts before opening the port.

```sh
export CHIPYARD=/absolute/path/to/compatible/chipyard
export HDMI_RELEASE=/absolute/path/to/extracted/release
export UART_TSI_DEVICE=/dev/serial/by-id/YOUR_UART_TSI_DEVICE
cd "$HDMI_RELEASE"
sha256sum -c SHA256SUMS
vivado -mode batch -source program-boralake-hdmi.tcl \
  -tclargs '<HW_SERVER>:3121' '<CABLE_TARGET>' '<FPGA_DEVICE>' \
  "$HDMI_RELEASE/BoraLakeHarness.bit"
"$CHIPYARD/generators/testchipip/uart_tsi/uart_tsi" \
  "+tty=$UART_TSI_DEVICE" +baudrate=921600 +selfcheck \
  +init_write=0x1000:0x80000000 +init_write=0x1004:0x0 \
  "$HDMI_RELEASE/hdmi-donut.elf"
```

Replace the final ELF to select another demo. Reprogram/reset before loading a different program to start cleanly. The new demos need this new bitstream: v0.1-rc1 lacks the page-flip registers. The UART-TSI loader requires a compatible built host executable; no printf/UART console is expected. Initialization disables/drains scanout, prepares two framebuffers, sets its DMA limit and base, then enables once. Thereafter each image is rendered into the inactive buffer, fenced, submitted and acknowledged before the old buffer is reused.

Programs/stack reside in the first 2 MiB starting at 0x80000000; framebuffers are reserved at 0x81000000 and 0x81100000. DMA attaches to coherent SBUS. Fences order stores before submission; do not remove them or reuse the front buffer early.

## Measure

See [VALIDATION.md](VALIDATION.md) for exact FPS/status commands, interpretation, compatibility restrictions and a reproducible comparison plan. `read-hdmi-status.py` samples original or latest registers safely.

Stop the existing UART-TSI host first, keeping the CPU running. Use the exact ELF that is currently running:

```sh
python "$HDMI_RELEASE/read-flip-stats.py" --chipyard "$CHIPYARD" \
  --elf "$HDMI_RELEASE/hdmi-donut.elf" --tty "$UART_TSI_DEVICE"
```

Add `--nm /path/to/riscv64-unknown-elf-nm` if needed. The helper performs read-only TSI initialization reads with `+no_hart0_msip`; it does not reload or restart the program. Average acknowledged presentation FPS is `(frames_switched-1)*100000000/elapsed_cycles`. The guarded snapshot can retry if a presentation occurs during reads. Rendering/wait-cycle symbols are documented in demo source/readmes. These are software counters, not measured monitor refresh or dropped-frame counts.

## Validation and limitations

- Clean full implementation: setup WNS **+0.003 ns**, TNS 0; hold WHS **+0.048 ns**, THS 0. Setup margin is just 3 ps.
- Expanded DRC: **0 errors, 288 warnings**, including 21 scanout-RAM REQP-1839 warnings. CDC retains **17 critical CDC-10 findings**. See `metrics/RESET-REVIEW.md` and full reports; no checks were waived to generate the bitstream.
- All 15 status-synchronizer FF checks and Gray-bus skew bounds passed. Two inputs/seven outputs lack external delay constraints. Passing STA is under supplied constraints, not complete board-level IO sign-off.
- Generated-controller simulation checks pixels, frame ordering, reordering, resets and late submissions. Normal tested page flips sustain ~59.95 Hz with zero transmitter resets/missing pixels. The serializer is stubbed in these tests.
- No FPGA programming was performed for this release. Hardware render FPS, reset robustness and HDMI electrical/display operation still require physical testing.

If blank: check monitor signal lock, cable/input, matched ELF/bitstream, FPGA programming logs and UART-TSI selfcheck. After resetting, try hold-bars. Persistent blank output requires on-board diagnosis; black pixels alone do not distinguish DMA underflow from signal loss.

## Rebuild

From the matching release source tag, activate the Chipyard environment and prepend its conda environment's bin directory so Java 20 precedes any Synopsys Java 8. Use the installed RISC-V toolchain prefix if different:

```sh
make -C software/hdmi-test demos hold CROSS=/path/to/bin/riscv64-unknown-elf-
python tests/hdmi/pageflip/test_animation.py
python tests/hdmi/pageflip/test_donut.py
python tests/hdmi/pageflip/test_plasma.py
python tests/hdmi/pageflip/test_tunnel.py
scripts/build-boralake-hdmi.sh verilog
env -u HDMI_INCREMENTAL_DCP scripts/build-boralake-hdmi.sh bitstream
```

Hardware output: `fpga/generated-src/chipyard.fpga.boralakeold.BoraLakeHarness.BoraLakeRocketConfig/obj/BoraLakeHarness.bit`. See `HDMI-WORKFLOW.md` for environment/submodule requirements and full RTL profiling commands. Submodule revisions were not changed. The included integration patch applies to clean base 064255862d8029809c57224ecf747c5f62ad6ead; it is not incremental for an already integrated tree. All downloaded release files are covered by SHA256SUMS.
