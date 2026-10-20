#!/usr/bin/env bash
# Source the Chipyard and Vivado/license environments before invoking in tmux.
set -euo pipefail
: "${chipyard:?Activate the Chipyard environment first}"
command -v vivado >/dev/null
export JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=8
cd "$chipyard/fpga"
fpga_dir=$(pwd -P)
config=TetheredEE290VCU118Config
name=chipyard.fpga.vcu118.VCU118FPGATestHarness.$config
build="$fpga_dir/generated-src/$name"
state="$fpga_dir/output/tethered-vcu118"
manifest="$build/$name.vsrcs.f"
mkdir -p "$state"
trap 'code=$?; if [ "$code" -ne 0 ]; then printf "FAILED (exit %s): see stage and logs\n" "$code" > "$state/result"; fi; date -Iseconds > "$state/finished"' EXIT
printf 'RUNNING\n' > "$state/result"
date -Iseconds > "$state/started"
printf 'ELABORATING\n' > "$state/stage"
make -j8 SUB_PROJECT=vcu118 CONFIG="$config" verilog > "$state/elaboration.log" 2>&1
printf '0\n' > "$state/elaboration-status"
printf 'PREPARING VIVADO SOURCES\n' > "$state/stage"
make -j8 SUB_PROJECT=vcu118 CONFIG="$config" "$manifest" > "$state/manifest.log" 2>&1
# An accidental simulation binder must never reach FPGA synthesis.
if rg -n '\b(SimDRAM|SimTSI|SimUART|SimJTAG|ClockSourceAtFreqMHz)\b' "$manifest" > "$state/simulation-model-check.log"; then
  echo 'Simulation models found in FPGA source manifest' >&2
  exit 1
fi
mapfile -t ip_tcls < <(find "$build" -name '*.vivado.tcl' -type f -print | sort)
sha256sum "$manifest" "$fpga_dir/src/main/scala/vcu118/TetheredConfigs.scala" > "$state/input-sha256"
printf 'VIVADO: SYNTHESIS, PLACEMENT, ROUTING, BITSTREAM\n' > "$state/stage"
cd "$build"
vivado -nojournal -mode batch -source "$fpga_dir/scripts/tethered-vcu118.tcl" \
  -tclargs -top-module VCU118FPGATestHarness -F "$manifest" -board vcu118 \
  -ip-vivado-tcls "${ip_tcls[*]}" > "$state/vivado.log" 2>&1
printf '0\n' > "$state/vivado-status"
bit="$build/obj/VCU118FPGATestHarness.bit"
test -s "$bit"
printf '%s\n' "$bit" > "$state/bitstream-path"
sha256sum "$bit" > "$state/bitstream-sha256"
printf 'FINISHED\n' > "$state/stage"
printf 'PASS: bitstream generated; review timing/DRC reports and test on hardware\n' > "$state/result"
