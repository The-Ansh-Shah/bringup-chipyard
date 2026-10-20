# Combined EE290 chip and bringup host on VCU118

`chipyard.fpga.vcu118.TetheredEE290VCU118Config` instantiates the Shuttle,
Saturn, and Atlas chip plus a coreless bringup host on one VCU118. The existing
`EE290VCU118Config` remains a separate chip-only target.

## Connections

- Chip 0: `EE290SimPeripheralConfig`, with board clocks and physical JTAG.
- Chip 1: `EE290BringupHostConfig(tsiToChip = true)`, with board DDR backing.
- SerialTL connects the two systems internally; there are no SerialTL pins.
- Host UART-TSI connects to the on-board USB-UART at 115,200 baud.
- The chip's separate peripheral UART uses the existing PMOD J52 binder
  (AT16 RX, AV15 TX). It is not the UART-TSI loading path.
- SDIO is disabled, preserving the existing non-overlapping PMOD JTAG layout.
- Both systems and the internal SerialTL link use 50 MHz. The FPGA shell's
  PLL/reset and DDR controller clock crossing provide the board interfaces.
- The cached chip DRAM window and host memory size both use `VCU118DDRSize`
  (2 GiB in the current shell), starting at `0x80000000`.

The UART-TSI route retains the coherent chip-memory access used by the passing
RTL simulations. Direct host-local UART-TSI DRAM access is not selected.
Other chip-advertised peripheral windows retain their existing host scratchpad
coverage; this is not a validation of every advertised address.

## Elaboration checks

Scala compilation, RTL elaboration, and Verilog generation passed on bwrcix-1.
The generated top contains exactly two systems, has no external SerialTL pads,
and contains no simulation clock, TSI, UART, JTAG, or DRAM models. Both cached
DRAM maps span `0x80000000` through `0xffffffff`. The generated shell constraints
assign 129 distinct package pins, including the existing UART/JTAG/DDR/reset
connections. Vivado accepted the PLL configuration and reported 50.0 MHz.
The completed implementation results are recorded below; hardware validation
remains separate.

## Build

Start from the personal fork's `tethered-sims` branch:

```bash
git clone --branch tethered-sims https://github.com/The-Ansh-Shah/bringup-chipyard.git
cd bringup-chipyard
```

Complete the repository's [initial setup](../Chipyard-Basics/Initial-Repo-Setup.rst)
to install the Chipyard environment, toolchain, CIRCT, and generator submodules.
Access to the submodule repositories in `.gitmodules` is required, including
the course Shuttle/Saturn/Rocket Chip repositories and Atlas. Initialize the
FPGA shell at its pinned revision as well:

```bash
git submodule update --init --recursive fpga/fpga-shells
```

The reference build used **Vivado 2021.2**, with VCU118/VU9P device support and
a working license. Vivado and its license must be available in the build shell.
After the standard Chipyard setup, a build on another installation is:

```bash
tmux new-session -s tethered-vcu118
source env.sh
export chipyard="$(pwd -P)"
# Load your installation's Vivado 2021.2 environment and license here.
bash fpga/scripts/build-tethered-vcu118.sh
```

On bwrcix-1, keep the build inside tmux. The activation paths below are local
to the reference workspace:

```bash
tmux new-session -s tethered-vcu118
source /tools/C/ansh_shah/setup/bringup/activate.sh
source /tools/C/ee194-sp26/vivado.sh
cd "$chipyard"
taskset -c 0-7 bash fpga/scripts/build-tethered-vcu118.sh
```

The script generates RTL and the source manifest, rejects simulation
models in that manifest, and runs the existing VCU118 Vivado flow through
`fpga/scripts/tethered-vcu118.tcl`. Make uses eight workers and Vivado's
`general.maxThreads` is eight. The flow checks timing after implementation;
a generated bit file alone is not treated as a successful build.

To generate RTL without running Vivado:

```bash
cd "$chipyard/fpga"
export JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=8
taskset -c 0-7 make -j8 SUB_PROJECT=vcu118 CONFIG=TetheredEE290VCU118Config verilog
```

## Results

The combined target completed the full Vivado flow on bwrcix-1 on
**October 16, 2026 at 16:55 PDT**, after FPGA synthesis, placement, routing,
bitstream generation, and final reports. Vivado and the
build launcher exited with status **0**. Wall time was approximately
**4 hours 52 minutes**, using at most eight build workers/CPUs.

| Reported timing metric | Result |
| --- | ---: |
| Worst setup slack (WNS) | +0.229 ns |
| Worst hold slack (WHS) | +0.010 ns |
| Worst pulse-width slack | +0.143 ns |
| Failing setup/hold/pulse-width endpoints | 0 / 0 / 0 |
| Unconstrained internal endpoints (maximum delay) | 0 |

These results cover the constraints present in the design. The reports still
identify five inputs and four outputs without I/O delays. Vivado also requests
a separate bus-skew report; the default flow did not produce one. DRC reported
920 warnings and no error-severity violations, including DSP pipeline advice,
UART/JTAG IOB constraints, and the existing JTAG clock placement override
(`CLOCK_DEDICATED_ROUTE = FALSE`). These constraints and warnings remain for
review before hardware qualification.

The reference `.bit` is approximately 63 MiB. Its verified SHA-256 is
`2ea6278778a761935678af69d8b248e664bf75d157172854d67760fdb52d75ad`.
This identifies the local reference artifact; byte-identical output from
another tool installation is not required. Generated artifacts are ignored
by Git and are not included in the fork.

Progress, result, source hashes, and logs are saved locally under
`fpga/output/tethered-vcu118/`. Inspect `stage`, `result`, `elaboration.log`,
and `vivado.log`. The output directory is ignored by Git. For immediate Vivado
progress, read `vivado.log` inside the generated design directory listed below;
the redirected console log under `output/` can be buffered during execution.

The bitstream path, when successfully generated, is recorded in
`fpga/output/tethered-vcu118/bitstream-path`; its hash is in `bitstream-sha256`.
The bitstream is `obj/VCU118FPGATestHarness.bit` under the generated design directory:
`fpga/generated-src/chipyard.fpga.vcu118.VCU118FPGATestHarness.TetheredEE290VCU118Config/`.
Timing, DRC, and utilization reports are under its `obj/report/` directory.

The passing combined RTL hello simulations are documented in
[the simulation guide](../Simulation/Tethered-EE290.md). The FPGA has not been
programmed, and board DDR calibration and hardware hello execution have not
been tested. The generated bitstream and reported timing results do not
establish those hardware results.
