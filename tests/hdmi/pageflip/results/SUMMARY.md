# Continuous HDMI timing and page flips: measured results

All measurements below are generated-controller simulation. The TMDS transmitter is stubbed; this does not establish physical monitor behavior. The previous design comparison uses the pipelined DMA before page flips, so it isolates the presentation/reset change rather than reusing the original single-read DMA baseline.

| Version/workload | Acknowledged changes | TX reset pulses | Missing pixels | Repeated frames | Pixel clocks/frame | New images/s |
|---|---:|---:|---:|---:|---:|---:|
| Previous stop/start: ideal | 8 | 8 | 0 | 0 | 412016–412017 | not a continuous-video FPS |
| New pageflip: ideal | 8 | 0 | 0 | 0 | 420000–420000 | 59.94945 |
| New pageflip: latency600 | 8 | 0 | 0 | 0 | 420000–420000 | 59.94945 |
| New pageflip: reordered | 8 | 0 | 0 | 0 | 420000–420000 | 59.94945 |
| New pageflip: late-submission | 8 | 0 | 0 | 8 | 420000–420000 | 29.97473 |

**Improvement:** software-induced transmitter resets fell from 8 to 0 for 8 changes: a 100% reduction. Normal, 600-cycle-memory-latency and delayed/out-of-order tests all present a different image every complete 420000-clock frame, about 59.94945 Hz in the testbench. Every visible pixel is checked against a coordinate-dependent pattern for its selected buffer, detecting tearing and wrong addresses/lanes. The out-of-order case actually reordered 17284 bursts; the runner fails if that case never reorders.

Late submissions intentionally miss the next DMA-frame selection deadline. The output remains 59.94945 Hz, repeats the old frame and presents new images at about 29.97473/s in this deliberately late workload. It never resets the transmitter or mixes buffers within a visible frame. This is not a claim that arbitrary CPU-rendered images can be produced 60 times/second.

Previous stop/start image-change cadence was numerically about 61.11/s, but those transitions restart/shorten video timing to about 412016–412017 clocks between image starts. That is not compliant continuous 800x525 timing and cannot be interpreted as a monitor-visible FPS advantage. Physical monitor reacquisition delay is not modeled.

Additional validation: Scala compilation/elaboration/Verilog generation succeeded; existing scanout regression and multi-source stop/drain/restart/reset/corrupt-response directed tests passed; 23 DMA profiling workloads completed with zero incorrect valid pixels. Intentional overload/blackout workloads still report missing pixels. Raw counters, source hashes and comparisons are attached here.

Nominal hardware mode is 640x480 at approximately 60 Hz: 800x525 total clocks, requested pixel frequency 25.175 MHz. The board PLL previously realized approximately 25.178571 MHz, equivalent to 59.94898 Hz, 16.68085 ms/frame and 36.83265 MB/s framebuffer payload. Exact realized clocks must be checked again in the new implementation report.

## Implementation status

The first register-array implementation was stopped during routing after congestion warnings and placed timing WNS -1.102 ns. It was not a qualified output. Its logs/checkpoints are archived under hdmi-validation/pageflip-attempt1.

The revised implementation registers MMIO requests (concurrency 1), uses SyncReadMem for the 512-byte reorder storage and a two-word (16-byte) elastic retirement queue with read-response credits. Fresh simulation reproduces the table above: 59.94945 Hz, zero resets/tearing/missing pixels, and actual reordered responses. Directed tests additionally verify that cancelled flips are not acknowledged, invalid commands are rejected, disabled timing continues and intentional disable does not set an underflow event.

Matched controller-only synthesis confirms one RAMB36 and reports 2476 LUTs / 6902 FFs, versus 3467 LUTs / 10567 FFs for the earlier pipelined register-array controller: -28.58% LUTs and -34.68% FFs despite adding the page-flip feature. Versus the original single-read controller (2460 LUTs / 6189 FFs), the latest version costs +0.65% LUTs / +11.52% FFs plus one RAMB36. These are controller-only synthesis estimates, not whole-board routed resource counts.

The revised 23-workload DMA profile also passes correctness. Under the periodic 20 us pause workload it records 18958 missing pixels/frame versus 18874.5 for the earlier FF pageflip version; the extra registered-read pipeline has a small stressed-latency cost. Normal, 600-cycle and reordered presentation tests still have zero missing pixels.

The clean full-board build completed successfully with Vivado 2024.2 for xc7k160tiffg676-2L. Setup WNS +0.003 ns / TNS 0; hold WHS +0.048 ns / THS 0; pulse width slack +0.264 ns. Setup margin is only 3 ps. Routed design: 50880 LUTs, 35334 FFs, 17 RAMB36, 96 RAMB18, 25 DSPs; scanout: 2357 LUTs, 6871 FFs, one RAMB36. There are zero unconstrained internal endpoints; 2 inputs and 7 outputs still lack external delay constraints.

Expanded DRC: 288 warnings, zero errors, including 21 REQP-1839 warnings on the scanout RAM. CDC retains 17 critical CDC-10 findings; this is not clean CDC sign-off. All 15 status synchronizer FFs and both Gray-bus skew checks passed (0.563 ns against 8 ns; 0.573 ns against 30 ns). See RESET-REVIEW.md. No new board programming or GitHub release update has been performed. Output: fpga/generated-src/chipyard.fpga.boralakeold.BoraLakeHarness.BoraLakeRocketConfig/obj/BoraLakeHarness.bit. Build log: hdmi-validation/pageflip-bram-bitstream.log.

