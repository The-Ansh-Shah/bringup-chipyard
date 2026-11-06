# Profiling the unchanged scanout controller

This provides a frozen original baseline and matched profiling of the subsequent pipelined DMA update. The published release remains the original hardware; profiling the new RTL does not replace that bitstream.

## Reproduce

To profile the current pipelined RTL at its default limit, generate normal BoraLake Verilog, then run from the checkout root:

```sh
scripts/build-boralake-hdmi.sh verilog
python tests/hdmi/profile/run.py --limit 4 --output hdmi-validation/profile-current --jobs 4
```

Use `--quick` for two smoke workloads (latency 0 and 200). Set VERILATOR to select a different executable. Results/logs are ignored build artifacts. Reviewed baseline results are copied into `tests/hdmi/profile/baseline/`. Do not overwrite that reference when measuring a candidate.

The runner compiles the actual emitted ClockSinkDomain_2 hierarchy, with only the HDMI transmitter stubbed. No copied implementation is measured. The wrapper handles both the original pruned single-source ports and the new three-bit response source IDs; it retains this config's 64-bit data / 32-bit DMA address interface. By default it returns complete bursts in request order. With --reorder, it adds deterministic per-request latency jitter and selects eligible bursts out of order; beats within each burst remain contiguous. It can accept up to 64 queued requests, and starts each one's latency timer on request acceptance.

## Experiment contract

- System clock: 100 MHz. Pixel half-period: 19.858 ns (approximately 25.1786 MHz); serializer clock is present but its logic is stubbed.
- Identical 640 x 480 timing and 64-word FIFO throughout this baseline. One warmup frame, then two complete measured frames.
- Memory returns 64-byte bursts, one 64-bit beat per system cycle when not stalled/backpressured. Fixed latency is an additional model wait; **use measured request-to-first-beat cycles**, rather than assuming the parameter equals observed latency.
- Once a response beat is offered, valid/data remain stable until accepted. Injected stalls delay new offers; they do not retract a stalled beat.
- Fixed latency sweep: 0, 25, 50, 75, 100, 110, 115, 120, 125, 140, 160, 200 and 400 system cycles.
- Periodic response pauses: 500 or 2000 system cycles out of every 5000, with latency 50. Pauses begin at measurement start.
- Pseudorandom offer stalls: 20% or 50%, latency 50, deterministic 32-bit LFSR seeds 1, 42, 12345. The configured probability is an offer-stall threshold, not a guaranteed fraction of every response beat or DRAM utilization.
- Recovery cases: a 100 us or 1 ms response blackout beginning 2 ms after measurement start, with latency 50.
- The framebuffer is a nonzero coordinate-dependent BGR565 hash. Every valid displayed pixel is compared with its expected x/y value; incorrect pixels, address order/range, occupancy bounds, wrong frame pixel counts or timeouts fail the run. Underflow must emit black and is counted separately. The synthetic memory contents are static across frames; this does not prove frame-version/page-flip correctness.

## Metrics

- **response_MBps**: accepted D-channel payload bytes divided by measurement wall time, decimal MB/s. Includes data that may later be discarded as stale.
- **useful_pixel_MBps**: correctly matched displayed pixels x 2 bytes divided by measurement time. This distinguishes productive scanout from wasted DMA traffic. Ideal throughput is capped by display consumption, not a free-running DMA saturation benchmark.
- **missing_pixels / missing_by_frame**: pixel slots with no matching FIFO word. A missing pixel is black; incorrect valid pixels are fatal.
- **average/max first latency**: request acceptance to first accepted response beat, including stalls and consumer backpressure. Completed-burst latency is recorded too. Counters are windowed by response events; a boundary-crossing request contributes its full latency if its response falls inside the window.
- **average/max outstanding**: accepted requests awaiting final response. Sampled once per system cycle.
- **FIFO occupancy**: accepted FIFO enqueues minus dequeues, including stale-word dequeues. Ideal testbench observation across the two clock domains; not a proposed unsynchronized hardware counter. Sampled once per system cycle, including blanking. The FIFO output staging register is not included.
- **first_recovered_cycles**: time from blackout end to first valid active pixel, or -1 if none/not applicable. This is first-pixel recovery, not proof that the entire image has recovered. Per-frame missing counts show sustained behavior.

results.json records workload parameters, all raw counts, tool version, commit, testbench hash and every emitted RTL dependency hash. Synthesis resource/timing numbers come from the existing Vivado report, not these simulations.

## Compare an optimization

Preserve FIFO capacity, clocks, workloads, seeds and observation windows to isolate DMA pipelining. The current implementation supports runtime limits 1, 2, 4, 8 through register +0x18. The runner writes this register only for the pipelined RTL; it leaves the original unchanged. Run the same harness into a different output directory, then:

```sh
python tests/hdmi/profile/compare.py \
  tests/hdmi/profile/baseline/results.json \
  hdmi-validation/profile-candidate/results.json \
  --output hdmi-validation/profile-candidate/COMPARISON.md
```

The comparison rejects mismatched experiment settings/workloads and incorrect pixels. If wiring/source-ID changes require a testbench edit, rerun the baseline RTL with that same revised harness before comparing; a changed harness hash deliberately rejects the comparison. It reports useful-throughput change and underflow reduction; zero denominators produce N/A, not fabricated percentages. Largest passing tested latency is a sampled bound: extend/refine the sweep if the candidate passes all points. Resource overhead needs a new synthesis/implementation report; ideal display bandwidth and refresh rate are not expected to improve.

These are synthetic controller-only experiments. They do not characterize real DDR, coherent-cache contention, arbitration fairness, analog HDMI, TMDS encoding or board behavior. No real-board performance improvement is claimed.

## Captured baseline

23 workloads completed with zero incorrect valid pixels; every case observed a maximum of one outstanding request. At observed fixed first-beat latency 1.42 us (model latency 140), all pixels displayed correctly. At 1.62 us (model latency 160), 304774 of 307200 pixel slots per frame were missing, despite 36.833 MB/s of accepted response traffic. Useful displayed bandwidth was only 0.291 MB/s. A 20 us response pause every 50 us lost an average 71688.5 pixels/frame. Both one-off blackouts recovered to a clean second measured frame. See baseline/REPORT.md and baseline/results.json for all metrics. These observations characterize the released single-request design, not a performance improvement.


## Pipelined-controller measurements

The new controller adds eight 64-byte reorder slots (512 extra payload bytes) while retaining the 64-word asynchronous FIFO. This is extra buffering compared with the release baseline. Every runtime limit uses the same synthesized hardware: comparing limit 1 with limit 4 isolates the admission/concurrency setting within that fixed design; comparing the release baseline with limit 4 measures the entire architectural change, including extra buffering.

```sh
# Extract original emitted RTL from the ignored baseline archive created earlier.
mkdir -p hdmi-validation/profile-frozen-rtl
unzip -oq hdmi-validation/profile-baseline/baseline-emitted-rtl.zip -d hdmi-validation/profile-frozen-rtl
python tests/hdmi/profile/run.py --rtl-dir hdmi-validation/profile-frozen-rtl \
  --output hdmi-validation/profile-original-final --jobs 2
for n in 1 2 4 8; do
  python tests/hdmi/profile/run.py --limit "$n" \
    --output "hdmi-validation/profile-pipeline-$n" --jobs 2
done
python tests/hdmi/profile/run.py --quick --limit 8 --reorder \
  --output hdmi-validation/profile-pipeline-reordered --jobs 2
python tests/hdmi/profile/directed.py
```

If the local archive is unavailable, generate the original RTL from commit c11c05a3 in an independent checkout, then supply its gen-collateral directory to --rtl-dir. Never edit the shared submodules to regenerate it. The revised harness is rerun against that original RTL so comparison hashes match.

`directed.py` instantiates the actual emitted pipelined controller and drives a corrupt-response test, multiple in-flight IDs, disable/drain, software restart and global reset. It verifies complete coordinate-pattern frames after restart/reset. The existing `python tests/hdmi/run.py` regression also remains required.

Matched area estimates can be obtained with `synth_resources.py --rtl-dir DIR --output DIR`: both designs use Vivado 2024.2 out-of-context controller synthesis for xc7k160tiffg676-2L, the real TMDS transmitter, the same options and no external board timing constraints. These are comparable synthesis-area estimates, not new full-board timing or DRC sign-off.


## Extended matched sweeps and comparison

The saved [pipeline-results/SUMMARY.md](pipeline-results/SUMMARY.md) includes the full 23-case workload suite plus latency-only waits 600, 800, 1000, 1200 and 1600 cycles: 28 workloads per configuration, 140 total. Raw JSON, CSV, matched-area counts and percentage tables are saved alongside it. The original baseline in baseline/ is retained as a historical record; pipeline-results/original.json reruns that original emitted RTL using the revised common harness.

For each configuration, run its normal suite and a supplemental sweep, then merge. Example for limit 4:

```sh
python tests/hdmi/profile/run.py --limit 4 \
  --output hdmi-validation/profile-pipeline-4 --jobs 2
python tests/hdmi/profile/run.py --limit 4 --latencies 600,800,1000,1200,1600 \
  --output hdmi-validation/profile-extra-limit4 --jobs 2
python tests/hdmi/profile/merge.py \
  hdmi-validation/profile-pipeline-4/results.json \
  hdmi-validation/profile-extra-limit4/results.json \
  --output hdmi-validation/profile-pipeline-4/merged.json
python tests/hdmi/profile/compare.py \
  tests/hdmi/profile/pipeline-results/original.json \
  hdmi-validation/profile-pipeline-4/merged.json \
  --output hdmi-validation/profile-pipeline-4/COMPARISON.md
```

Use limits 1, 2 or 8 identically. For the original, use --rtl-dir pointing at its emitted collateral and --limit 1 for both sweeps. merge.py rejects changed RTL, settings or duplicate workloads. compare.py rejects changed testbench hashes; rerun the original if you intentionally change the harness.
