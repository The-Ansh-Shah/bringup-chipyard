#!/usr/bin/env bash
set -euo pipefail
repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
# Use the existing Chipyard environment on this host, or set CHIPYARD_ENV_ROOT.
env_root=${CHIPYARD_ENV_ROOT:-/bwrcq/C/ansh_shah/ee194-lab1}
set +u # Existing Conda activation hooks require unset backup variables.
source "$env_root/env.sh"
set -u
export PATH="$env_root/.conda-env/bin:$PATH"
unset USE_CHISEL7
export USE_CHISEL6=1
mkdir -p "$repo/.java_tmp"
exec make -C "$repo/fpga" \
 SUB_PROJECT=boralakeold SBT_PROJECT=chipyard_fpga \
 MODEL=BoraLakeHarness VLOG_MODEL=BoraLakeHarness \
 MODEL_PACKAGE=chipyard.fpga.boralakeold \
 CONFIG=BoraLakeRocketConfig CONFIG_PACKAGE=chipyard.fpga.boralakeold \
 GENERATOR_PACKAGE=chipyard TOP=ChipTop TB=none BOARD=boralake FPGA_BRAND=xilinx \
 "${@:-firrtl}"
