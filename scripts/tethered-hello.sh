#!/usr/bin/env bash
# Preloaded hello on the combined chip + bringup host. Source activate.sh first.
set -uo pipefail
: "${chipyard:?Source the bringup-chipyard environment first}"
cd "$chipyard/sims/verilator"
config=${1:-TetheredEE290FastUARTConfig}
case "$config" in
  TetheredEE290FastUARTConfig) state=output/tethered-hello ;;
  TetheredEE290Config) state=output/tethered-original-hello ;;
  *) echo "Unsupported tethered config: $config" >&2; exit 2 ;;
esac
mkdir -p "$state"
binary="$chipyard/tests/build/hello.riscv"
sim=./simulator-chipyard.harness-$config
if [ ! -x "$sim" ] || [ ! -f "$binary" ]; then
  echo "Build $config and tests/build/hello.riscv first." >&2
  exit 1
fi
# The combined Verilator model needs more than the default 8 MiB stack.
ulimit -s 262144
# UART-TSI/HTIF is slow; retain both a wall-time and simulation-cycle bound.
printf 'RUNNING\n' > "$state/result"
printf 'RUNNING\n' > "$state/exit-status"
printf '%s\n' "$config" > "$state/config"
SECONDS=0
timeout -k 5 "${TETHERED_HELLO_TIMEOUT:-1800}" stdbuf -oL -eL "$sim" \
  +permissive +max-cycles="${TETHERED_HELLO_MAX_CYCLES:-1000000}" +loadmem="$binary" \
  +permissive-off "$binary" </dev/null > "$state/run.log" 2>&1
code=$?
printf '%s\n' "$code" > "$state/exit-status"
printf '%s\n' "$SECONDS" > "$state/elapsed-seconds"
cat "$state/run.log"
if [ "$code" -eq 0 ] && grep -q 'Hello world from core 0' "$state/run.log"; then
  echo 'PASS: tethered hello printed and simulator exited successfully' | tee "$state/result"
  exit 0
fi
echo "NOT PASSED: tethered hello exit status $code; inspect $state/run.log" | tee "$state/result"
exit 1
