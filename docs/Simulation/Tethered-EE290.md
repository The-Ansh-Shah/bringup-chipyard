# EE290 tethered RTL hello

This simulation contains `EE290SimPeripheralConfig` (Shuttle, Saturn, Atlas)
connected by SerialTL to the coreless `EE290BringupHostConfig`.
`TetheredEE290FastUARTConfig` uses a 500 MHz harness and host, and 12.5 Mbaud
UART-TSI. It is a simulation configuration, not a VCU118 configuration.

## Verified results

Both configurations ran the preloaded `tests/build/hello.riscv`, printed
`Hello world from core 0, a unknown`, and exited with simulator status **0**.
The `unknown` text is the program's architecture-name fallback; it does not
indicate a test failure. Runtimes below exclude simulator builds.

| Configuration | UART baud | Hello runtime | Saved results (relative to `sims/verilator/`) |
| --- | --- | --- | --- |
| `TetheredEE290FastUARTConfig` | 12,500,000 | 799 seconds (13m 19s) | `output/tethered-hello/` |
| `TetheredEE290Config` (corrected) | 921,600 | 3,995 seconds (1h 6m 35s) | `output/tethered-original-hello/` |

Each result directory contains `run.log`, `result`, `exit-status`, and
`elapsed-seconds`. These generated logs remain local and are not included in
the source commit. Both passing simulators were built with temporary diagnostic
prints removed. The original-config overnight job finished successfully on
October 16, 2026 at 00:38 PDT, including a successful simulator build.

This validates CPU boot, execution from host DRAM over SerialTL, HTIF output,
and program completion for hello. It does not validate Saturn or Atlas/DMA
workloads, the full memory range, or VCU118 board integration.

## HTIF routing

Tethered mode uses `EE290BringupHostConfig(tsiToChip = true)`.
UART-TSI masters the host OBUS directly and accesses the chip through SerialTL,
including DRAM addresses. The chip's FBUS provides coherence with its caches.
Chip memory requests travel back over SerialTL into the host FBUS and local
DRAM. The host SBUS does not connect back to OBUS in this mode, avoiding a
routing loop and overlapping local/remote memory managers. OBUS retains its
clock feed from SBUS.

With the original host-local UART-TSI memory path, CPU boot and instruction
fetch worked, but HTIF did not: the CPU spun at `0x80001a28` waiting for
`fromhost` after storing `tohost`, while host DRAM still contained zero at
`tohost=0x80001e00` and `fromhost=0x80001e08`. Host-local reads bypassed the
chip's dirty cache line. A fence alone does not write back that cache line.
The standalone host default remains unchanged.

## Reproduce on bwrcix-1

Keep builds and tests inside tmux. Do not enable `set -u` before activation.

```bash
tmux new-session -s tethered-hello
source /tools/C/ansh_shah/setup/bringup/activate.sh
cd "$chipyard/tests"
cmake -S . -B build -D CMAKE_BUILD_TYPE=Debug \
  -D CMAKE_C_COMPILER=riscv64-unknown-elf-gcc \
  -D CMAKE_CXX_COMPILER=riscv64-unknown-elf-g++
cmake --build build --target hello -j4
cd "$chipyard/sims/verilator"
export JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=8
taskset -c 0-7 make -j8 CONFIG=TetheredEE290FastUARTConfig CONFIG_PACKAGE=chipyard
cd "$chipyard"
taskset -c 0-7 bash scripts/tethered-hello.sh
```

The hello script redirects stdin from `/dev/null`, preloads the ELF, enables
normal CPU wakeup, raises the stack limit to 256 MiB for the combined Verilator
model, and requires both `Hello world from core 0` and simulator
exit status zero. Its default bounds are 1,000,000 cycles and 1,800 seconds;
`TETHERED_HELLO_TIMEOUT` can override the wall-time bound, and
`TETHERED_HELLO_MAX_CYCLES` can override the cycle bound. Results are saved in
`sims/verilator/output/tethered-hello/`.

Diagnostic logs from the failure are in `output/tethered-traced-hello/`
(relative to `sims/verilator`), including `dram-snapshot.log`. The trace showed
MSIP reaching the chip and execution at `0x80000000` around cycle 10,518.
Temporary trace prints are not part of the source fix.

The chip advertises 32 GiB of DRAM; the host currently backs 16 GiB. This test
uses low DRAM addresses only and does not validate the full advertised range.
The original slower-baud `TetheredEE290Config` is not the fast smoke-test target.

## Original-config overnight validation

`TetheredEE290Config` now explicitly uses a 500 MHz top-level harness clock,
matching the host UART clock used by the simulated UART adapter. Its UART
baud remains 921,600. This is validation of the corrected original config,
not the unchanged historical config.

With the environment activated, build and run inside tmux:

```bash
cd "$chipyard/sims/verilator"
export JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=8
taskset -c 0-7 make -j8 CONFIG=TetheredEE290Config CONFIG_PACKAGE=chipyard
cd "$chipyard"
TETHERED_HELLO_TIMEOUT=28800 TETHERED_HELLO_MAX_CYCLES=10000000 \
  taskset -c 0-7 bash scripts/tethered-hello.sh TetheredEE290Config
```

This keeps original-config results in `sims/verilator/output/tethered-original-hello/`
and preserves the fast-config results. The higher cycle limit accommodates
UART traffic at the slower baud. Pass still requires both the hello message
and simulator exit status zero.

The completed overnight job used tmux session `tethered-original-overnight`.
Its exact launcher is saved in
`sims/verilator/output/tethered-original-overnight/job.sh`; its `stage`,
`result`, `build.log`, and `runner.log` record its successful outcome. The
launcher used a one-hour build limit followed by an eight-hour run limit;
the job finished well within those bounds.
