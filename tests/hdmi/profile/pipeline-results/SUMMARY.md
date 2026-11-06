# Pipelined HDMI DMA: measured comparison

Controller RTL compiled, elaborated and generated successfully. 140 matched profile workloads completed (28 per configuration), with zero incorrect valid pixels. Underflow in deliberately overloaded cases is counted, not hidden. The functional regression and directed multi-source stop/drain, restart, reset and corrupt-response tests passed. Two additional out-of-order response workloads passed with actual reordered bursts.

100 MHz system, approximately 25.1786 MHz pixel clock, 640x480 BGR565, 64 FIFO entries. Each workload warms up for one frame and measures two frames. These are deterministic synthetic memory experiments, not measurements of board DDR/coherence or physical HDMI.

## Latency tolerance and periodic stalls

| Configuration | Largest tested passing memory wait (cycles) | Change vs original sampled bound | Missing pixels/frame, 20 us pause every 50 us | Underflow reduction vs original | Useful MB/s in that pause workload |
|---|---:|---:|---:|---:|---:|
| Released original (1 read) | 140 | +0.0% | 71,688.5 | +0.00% | 28.238 |
| New controller, limit 1 | 125 | -10.7% | 71,202.0 | +0.68% | 28.296 |
| New controller, limit 2 | 200 | +42.9% | 42,652.0 | +40.50% | 31.719 |
| New controller, limit 4 (default) | 600 | +328.6% | 18,874.5 | +73.67% | 34.570 |
| New controller, limit 8 | 1200 | +757.1% | 1,813.0 | +97.47% | 36.616 |

The wait is the configured additional model latency; measured first-beat latency is recorded separately. Passing thresholds are sampled bounds, not proven maxima. All configurations were tested at the same latency points, including supplemental waits 600, 800, 1000, 1200 and 1600 cycles.

At wait 400, the new limit-2 configuration loses every pixel while limits 4 and 8 display every pixel. This provides a controlled concurrency comparison within identical physical hardware.

## Buffering and area cost

The released controller has 512 bytes of asynchronous FIFO payload storage. The new controller retains that FIFO and adds 512 bytes of response/reorder payload storage plus metadata. Original-to-new improvements include that added buffering. Every new runtime limit uses the same eight-slot hardware; comparisons between those limits keep physical storage constant. The new limit-1 setting can perform worse than the original because it waits for a complete burst and ordered retirement before freeing the slot.

| Matched controller-only synthesis | Original | New | Change |
|---|---:|---:|---:|
| LUTs | 2,460 | 3,467 | +40.93% |
| Flip-flops | 6,189 | 10,567 | +70.74% |
| RAMB36 / RAMB18 | 0 / 0 | 0 / 0 | unchanged |

Vivado 2024.2, xc7k160tiffg676-2L, out-of-context ClockSinkDomain_2 synthesis, real TMDS RTL, identical flags, no board timing constraints. Not post-route timing/DRC sign-off.

## Scope and artifacts

- New runtime register at 0x10081018: four-bit queued-burst limit, reset default 4; effective admission clamps to 1..8. Change while disabled and idle for controlled measurements.
- Eight source IDs; whole bursts may return out of request order, then retire in framebuffer order. Each request reserves room for all eight beats, so responses never wait for pixel FIFO capacity.
- A request already offered is held until accepted even after software disable. Disable drains issued reads and discards them before reporting idle.
- Existing framebuffer/control registers and test ELF remain compatible. Video timing, serializer, clocks, reset crossings and the FPGA board shell were not changed.
- No new full-board implementation or timing/DRC sign-off was run. Existing local/release bitstreams remain the original design; do not describe them as containing this update.
- No GitHub push, release modification or board programming was performed for this optimization.
- Raw metrics: original.json and limit1/2/4/8.json; combined metrics.csv; area.json. Percentage tables include both original-to-new and controlled limit-1 comparisons.
- Reproduction: ../README.md. Full logs: hdmi-validation/profile-* and pipeline-*.log.
