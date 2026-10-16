#!/usr/bin/env bash
# Host UART-TSI -> SerialTL -> chip CLINT register smoke test.
# Run from an activated bringup-chipyard environment. No CPU program is started.
set -uo pipefail
: "${chipyard:?Source the bringup-chipyard environment first}"
state="$chipyard/sims/verilator/output/tethered-chip-sanity"
mkdir -p "$state"
cd "$chipyard/sims/verilator"
binary="$chipyard/tests/build/hello.riscv"
sim=./simulator-chipyard.harness-TetheredEE290FastUARTConfig
if [ ! -x "$sim" ] || [ ! -f "$binary" ]; then
  echo 'Build TetheredEE290FastUARTConfig and tests/build/hello.riscv first.' >&2
  exit 1
fi
# Match the stack limit required by the combined Verilator model.
ulimit -s 262144
# +loadmem triggers HTIF init commands without UART-uploading the ELF.
# +no_hart0_msip avoids waking the CPU from the boot ROM.
# Keep MTIMECMP[63:32] high while testing its low word, then restore both words high.
timeout -k 5 90 stdbuf -oL -eL "$sim" \
  +permissive +max-cycles=300000 \
  +loadmem="$binary" +no_hart0_msip \
  +init_write=0x02004004:0xffffffff \
  +init_write=0x02004000:0x1234abcd +init_read=0x02004000 \
  +init_write=0x02004000:0xffffffff +init_read=0x02004000 \
  +permissive-off "$binary" </dev/null > "$state/run.log" 2>&1
sim_status=$?
printf '%s\n' "$sim_status" > "$state/simulator-exit-status"
cat "$state/run.log"
# The CPU stays in its boot ROM, so simulation itself has no program-completion
# signal. Accept a bounded cycle or wall timeout only after both readbacks pass.
if grep -q 'Reading 2004000 .*got 1234abcd' "$state/run.log" && \
   grep -q 'Reading 2004000 .*got ffffffff' "$state/run.log" && \
   { [ "$sim_status" -eq 0 ] || [ "$sim_status" -eq 124 ] || grep -q 'FAILED.*(timeout).*300001 simulation cycles' "$state/run.log"; }; then
  echo 'PASS: chip CLINT write/read/restore across SerialTL' | tee "$state/result"
  exit 0
fi
echo 'NOT PASSED: chip CLINT readbacks incomplete or unexpected simulator failure' | tee "$state/result"
exit 1
