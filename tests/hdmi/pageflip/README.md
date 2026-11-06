# Continuous timing and page-flip measurement

The new RTL keeps 800 x 525 total pixel timing running across software disable and page flips. Only hardware/PLL reset resets the transmitter. Disabled/unprimed scanout emits valid black video. At startup the pixel stream aligns to a complete frame boundary; this may add one initial black frame. It does not interrupt the HDMI clock/sync.

One flip snapshots a new base address and chooses it for the next DMA frame that has not yet begun fetching. DMA can prefetch during the preceding visible frame; per-word frame/epoch tags prevent that data from becoming visible until its own frame. The first visible pixel commits presentation. A synchronized acknowledgement then allows software to reuse the old buffer. Keep scanout enabled until that acknowledgement; if software disables/cancels, first drain and establish a new completion-count baseline before another flip. There is one outstanding flip command; late requests repeat the previous image for another frame rather than tearing or resetting HDMI.

## New register interface (MMIO base 0x10081000)

| Offset | Access | Meaning |
|---|---|---|
| 0x10 | RW64 | Initial framebuffer base; change only while scanout disabled and DMA idle |
| 0x18 | RW | DMA allocated-burst limit, default 4, clamped 1..8 |
| 0x20 | RW64 | Next framebuffer address; snapped by submit |
| 0x28 | R | bit 0 flip busy, bit 1 awaiting DMA frame selection, bit 2 rejected request |
| 0x28 | W | Write bit 0 = 1 to submit. Must be enabled, address 64-byte aligned and in valid RAM. Hardware checks enable/alignment; software must check memory range |
| 0x30 | R32 | Number of acknowledged presentations since hardware reset |
| 0x38 | R64 | Last acknowledged framebuffer base (initial base while stopped) |
| 0x40 | R | bit 0 underflow-ever sticky until hardware reset |

Original status/enable offsets remain. Use mapped framebuffer memory [0x80000000,0xc0000000) and reserve an entire 614400-byte frame. Write/fence the inactive buffer, submit, then wait for 0x30 to advance before modifying the old buffer. Do not disable scanout for a normal flip. Requests while busy are backpressured; read status before submitting. Changes to the next-base staging register after submission do not change the snapped request.

## Reproduce simulation metrics

Generate the normal FPGA Verilog first:

```sh
scripts/build-boralake-hdmi.sh verilog
python tests/hdmi/pageflip/run.py --output hdmi-validation/pageflip-current --jobs 3
python tests/hdmi/run.py
python tests/hdmi/profile/directed.py
```

The pageflip runner checks actual generated controller RTL and stubs only the transmitter. It reads completion through the real TileLink MMIO port, returns coordinate-dependent pixels from two different framebuffers, checks every visible pixel against one image for the entire frame, checks complete pixel counts, exact 420000-pixel frame periods, missing pixels, repeated images, reset pulses and acknowledged presentation cadence. Cases cover ideal memory, 600-cycle latency, delayed/out-of-order bursts, and deliberately late submissions. The late case must stay synchronized and untorn while repeating frames; it is not expected to deliver a new image every frame.

The pre-change comparison is:

```sh
python tests/hdmi/pageflip/run.py --legacy \
  --rtl-dir hdmi-validation/pre-pageflip/gen-collateral \
  --output hdmi-validation/pageflip-legacy
```

That frozen collateral was copied before the RTL edit. The same testbench uses the old disable/drain/change-base/enable sequence for that comparison, showing reset pulses and shortened/restarted timing. Its numerical image-change cadence is not a valid monitor refresh-rate result because the video stream is interrupted. It does not simulate how long a physical monitor takes to reacquire.

## Approximately 60 Hz, and what this proves

800 x 525 = 420000 pixel clock cycles/frame. Requested 25.175 MHz is a standard nominal 60 Hz VGA mode. The checked board PLL realizes approximately 25.178571 MHz, yielding about 59.949 Hz and 16.681 ms/frame. The testbench uses a 19.858 ns pixel half-period, a close approximation. We do not claim exactly 60.000 Hz.

640 x 480 x 2 = 614400 payload bytes/frame, about 36.833 MB/s at this cadence. Active pixels require about 50.357 MB/s because blanking consumes the rest of each frame. Pipelined DMA and buffering must meet that delivery schedule. A late software render can repeat a frame even while video timing remains near 60 Hz.

`hdmi-flip-stress.elf` pre-renders two buffers, then submits alternating images immediately after each acknowledgement. It measures presentation capacity independently of CPU rendering. It is not proof that Rocket can draw an arbitrary full image 60 times/second. That requires profiling rendering and coherence traffic separately.

## Software and hardware build

```sh
make -C software/hdmi-test pageflip
scripts/build-boralake-hdmi.sh bitstream
```

Pair the NEW bitstream with `software/hdmi-test/build/hdmi-pageflip.elf` (two-second color cycling through page flips) or `hdmi-flip-stress.elf` (pre-rendered maximum-rate flips). The old release bitstream does not implement these registers. The original cycling ELF still uses disable/re-enable; with new hardware it preserves signal timing but may show black video while the restarted stream aligns. Use the new pageflip ELF for seamless changes.

Program/load with the existing documented JTAG and 921600-baud UART-TSI procedure. No board is programmed by these build commands. In flip-stress software, diagnose `hdmi_frames_switched`, `hdmi_elapsed_cycles`, `hdmi_interval_cycles_last/max`, `hdmi_flip_wait_cycles_last/max`, `hdmi_status`, and trap symbols using nm/TSI. With system clock 100 MHz, average acknowledged FPS is `(hdmi_frames_switched - 1) * 100000000 / hdmi_elapsed_cycles` after at least two flips. These counters are updated live. Read hdmi_stats_seq before and after reading the cadence counters; accept a snapshot only if both sequence values are equal and even. The sequence covers cadence/phase/status fields updated in flip-stress main, not the independently updated flip_to wait-time diagnostics.

Review full-board timing, hold, DRC, CDC and Gray-bus-skew reports before treating a generated bitstream as ready for a hardware trial. Controller simulation does not verify DDR calibration, TMDS encoding/serialization or physical display behavior. The user's earlier intermittent blue/black observation does not establish full HDMI correctness.

### Read real-board cadence counters

After loading/running hdmi-flip-stress.elf, stop the polling UART-TSI host before opening another client (the CPU program continues). With the correct Chipyard environment and sole access to the serial port:

```sh
python software/hdmi-test/read-flip-stats.py \
  --chipyard "$CHIPYARD" --tty "$UART_TSI_DEVICE" \
  --elf software/hdmi-test/build/hdmi-flip-stress.elf
```

The helper derives addresses from the matching ELF, performs ordered 32-bit reads with +no_hart0_msip, reconstructs little-endian 64-bit counters, and accepts only equal even sequence values around the sample. It terminates the temporary diagnostic host after all reads. Set --nm for a non-PATH cross-toolchain, or --dry-run to print the command without opening UART. Parser, FPS arithmetic and odd-sequence rejection were verified against a mock host; real-board readback remains untested. The existing UART tool checks for stale serial bytes on connection, so a protocol/port failure requires resolving that before retrying.
