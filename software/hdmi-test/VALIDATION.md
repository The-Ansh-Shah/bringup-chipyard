# Validate BoraLake HDMI: original and pipelined releases

These procedures are host commands for the recipient. They were checked against source, ELF symbols and mocked UART-TSI output; they have not been run on a connected board by the agent. Native renderer tests are not measurements of Rocket FPS. Both bitstreams target xc7k160tiffg676-2L and the same nominal640x480/~59.95Hz mode.

## 1. Choose compatible software

| Bitstream | Compatible supplied programs | Measurement |
|---|---|---|
| Original v0.1-rc1 | Original hdmi-test.elf and hdmi-hold-bars.elf | Sample per-frame underflow and DMA-error registers; no acknowledged-FPS counter |
| Latest v0.2-rc1 | Donut, plasma, tunnel, smiley, pageflip, flip-stress, flip-stress-8, hold-bars | Acknowledged presentation FPS, per-frame/sticky underflow, DMA errors |

**Do not run the new page-flip demos on the original bitstream.** Registers at offsets0x18 and0x20..0x40 do not exist there. Waiting for a flip acknowledgement will fail/stall, which is not evidence of poor DMA performance. The original cycling test changes images about every two seconds and restarts scanout during updates. Black periods around those updates alone do not prove underflow or HDMI signal loss.

## 2. Establish a controlled run

Follow the release README's programming/loading commands. Use the same physical board, HDMI cable/input, display and UART baud921600. Reset/reprogram before each independent run. Record release tag, bitstream SHA256, exact ELF, test length and display signal-lock status. Start with hold-bars to distinguish basic display operation from rendering load. Do not have two UART-TSI processes open the same serial device.

Set paths after extracting the bundle:

```sh
export CHIPYARD=/absolute/path/to/compatible/chipyard
export HDMI_RELEASE=/absolute/path/to/extracted/release
export UART_TSI_DEVICE=/dev/serial/by-id/YOUR_UART_TSI_DEVICE
```

To take measurements, terminate only the original host UART-TSI process (for example Ctrl-C in its terminal); leave the board powered and do not reset/reprogram. The target CPU should continue running. The helpers open that serial port exclusively, perform `+init_read` transactions with `+no_hart0_msip` and `none` instead of an ELF, collect output and close the host. They do not issue memory writes, reload an ELF or start the hart. A compatible Chipyard UART-TSI executable is required.

## 3. Read actual animation presentation FPS (latest only)

After running the donut for at least10seconds and stopping its host as above:

```sh
python "$HDMI_RELEASE/read-flip-stats.py" \
  --chipyard "$CHIPYARD" --tty "$UART_TSI_DEVICE" \
  --elf "$HDMI_RELEASE/hdmi-donut.elf"
```

Substitute the exact currently running ELF for plasma/tunnel/smiley/flip-stress. If RISC-V nm is not on PATH, add `--nm /absolute/path/to/riscv64-unknown-elf-nm`. Add `--dry-run` to inspect the command without accessing the board. The ELF is used to locate actual symbols; using a different ELF gives invalid addresses.

- `average_presented_fps = (hdmi_frames_switched-1)*100000000/hdmi_elapsed_cycles`, once at least two flips are acknowledged. The system clock is100MHz. Counters cover the interval from the first acknowledged flip to the last captured flip, excluding initial setup. Read again later for a longer-run average.
- Near59.95 means approximately one acknowledged new image each refresh. Near29.97 is consistent with every other refresh. Actual values can vary with rendering/submission deadlines.
- `hdmi_interval_cycles_last/max / 100000` gives milliseconds per presentation interval (1ms=100000cycles). About1668085cycles is one nominal refresh; about3336170 is two. A maximum includes all intervals since boot.
- This is **acknowledged presentation cadence**, not monitor-detected HDMI refresh, CPU-only render speed, or proof that every physical pixel is correct. The pre-rendered flip test intentionally excludes rendering cost.
- Renderers also expose `hdmi_render_cycles_last/max/total` and `hdmi_slow_present_intervals`; the current helper does not read these fields. They are symbols for further diagnostics. A slow interval threshold of25ms is not an exact dropped-frame counter.

The helper requires equal, even sequence guards around its reads. If it cannot obtain a consistent snapshot in five attempts, retry; it does not return a partial/torn sample. If it reports missing symbols, check the matching ELF. Do not use this helper with original rc1 ELFs.

## 4. Read underflow and DMA errors (both releases)

Both bundles include read-hdmi-status.py. Original:

```sh
python "$HDMI_RELEASE/read-hdmi-status.py" \
  --chipyard "$CHIPYARD" --tty "$UART_TSI_DEVICE" \
  --version original --samples 30 --interval 1 | tee original-status.jsonl
```

Latest:

```sh
python "$HDMI_RELEASE/read-hdmi-status.py" \
  --chipyard "$CHIPYARD" --tty "$UART_TSI_DEVICE" \
  --version latest --samples 30 --interval 1 | tee latest-status.jsonl
```

Only use `--version latest` with the new hardware; it additionally reads0x10081040, unmapped on the original. Samples are serial transactions, not atomic snapshots. The interval is an extra pause after each sample; connection/transaction overhead increases actual spacing. No measurements are taken while another host owns the port. `--dry-run` works for either version.

| Output | Register / interpretation |
|---|---|
| underflow_this_frame | 0x10081004 bit0. Set when a visible pixel has no matching data; clears during vertical sync. A true sample proves a missed deadline in that frame. False samples do not prove an underflow-free run. |
| busy | 0x1008100c bit0. Requests remain active/allocated; busy during scanout is normal. |
| video_ready | 0x1008100c bit1. Synchronized deassertion of video reset; useful clock/reset readiness flag, not proof of HDMI cable/display lock. |
| dma_error | 0x1008100c bit2. A denied/corrupt DMA response was observed. This is distinct from a latency-induced underflow. Clears when disabled/idle. |
| underflow_ever | Latest only:0x10081040 bit0, sticky until hardware reset. True means at least one underflow occurred since reset, including startup; it is not a pixel/frame count. |

The latest demos' `hdmi_status` software snapshot packs bit0=sticky underflow and bit34=DMA error (register0x0c bit2 shifted by32). To decode JSON from read-flip-stats.py, save output to fps.json and run:

```sh
python - <<'PY'
import json
s=json.load(open('fps.json'))
v=s['hdmi_status']
print('FPS:',s['average_presented_fps'])
print('Underflow observed:',bool(v & 1))
print('DMA response error:',bool(v & (1 << 34)))
PY
```

This snapshot updates after acknowledged flips; use the direct status helper if the program has stopped making progress. Neither original nor latest bitstream has a total missing-pixel counter. The original has no sticky underflow-ever register. Serial sampling can miss its per-frame flag, especially near vsync; report **observed flags**, never a percentage of lost pixels or proof of zero underflows from all-false samples.

## 5. Interpret results and compare fairly

| Result | Interpretation / next step |
|---|---|
| ~59.95 acknowledgedFPS, no sticky underflow or DMA errors | Supports full-rate presentation during this run; confirm visible image correctness and monitor lock. |
| Lower FPS, no underflow | Rendering or flip submission may be late. Hardware may safely repeat old frames; lowFPS alone does not establish DMA failure. |
| Underflow flag | At least one pixel deadline was missed. Check memory contention/latency, reset/startup and scanout behavior; the flag alone does not identify the cause. |
| DMA error | Investigate memory mapping/transaction error, distinct from slow rendering. |
| Blank monitor with no underflow | Check signal lock, cable/input, serializer, clock/reset and FPGA programming; a valid RAM stream does not prove valid HDMI. |

**Four vs eight requests:** on the latest bitstream, run hdmi-flip-stress.elf (four) and hdmi-flip-stress-8.elf (eight) separately after reset. Keep all other conditions equal. Record FPS, longest interval, underflow flags and visible behavior. Both may already achieve the same maximum59.95FPS; that is a valid result, not evidence of an eight-slot benefit. This compares limits on the new architecture, not old-vs-newRTL.

**Original vs latest:** run the **same original hdmi-hold-bars.elf** on both bitstreams, sampling status with the correct helper version and observing lock/artifacts. This is a compatible static-scanout smoke test, not a rendering or saturation benchmark. It cannot demonstrate improved maximumFPS, and sparse original status samples cannot establish its exact underflow rate. A controlled old-vs-new performance comparison still needs a shared workload/measurement scheme that both register maps support; the new page-flip demos are not that benchmark. Existing controlled RTL simulations provide separate synthetic latency/stall comparisons, with the serializer stubbed.

Suggested record: tag+hash, ELF+hash, configuredDMA limit, observation time, monitor lock/visible result, acknowledgedFPS when available, maxinterval, observed underflow, stickyunderflow when available, DMAerrors. Keep logs and distinguish unavailable metrics from zero.

## 6. Verify tools and claims without hardware

In the latest source checkout:

```sh
python tests/hdmi/pageflip/test_status_reader.py
python tests/hdmi/pageflip/test_animation.py
python tests/hdmi/pageflip/test_donut.py
python tests/hdmi/pageflip/test_plasma.py
python tests/hdmi/pageflip/test_tunnel.py
```

The status-reader test mocks the host and verifies read-only arguments, version-safe register addresses, decoding and repeat sampling. Register map source: generators/barduino-blocks/src/main/scala/VideoController/VideoController.scala, original hardware commit c11c05a3 and new release commit625f51fa. Pixel/stall simulation commands and raw results: tests/hdmi/pageflip/README.md and tests/hdmi/profile/README.md. Reports in each release document implementation findings; they are not physical measurements.
