# BoraLake HDMI test

This ELF targets the modified BoraLakeRocketConfig in this integration, not the original two-register controller. Physical HDMI behavior has not been verified.

Build from the worktree root:

```
make -C software/hdmi-test
```

Override `CROSS=/path/to/riscv64-unknown-elf-` when needed. Output: `software/hdmi-test/build/hdmi-test.elf`. The test has a small standalone M-mode startup using the checkout's UART-TSI BootROM convention (entry at 0x80000000), not the SophiaLake console/runtime, because this config has no console UART. Linker layout follows the existing baremetal-ide separation of PT_LOAD and NOLOAD stack/BSS to avoid slow host zero-filling. It uses the checkout's installed GCC and existing UART-TSI executable.

The program first shows eight vertical color bars, then red, green, blue, repeating at about two seconds per image. It fills the inactive buffer, fences, disables scanout, waits for DMA idle, allows both clock domains to observe reset, switches base and enables. Brief black/resynchronization intervals during buffer switches are expected; this is not a seamless page-flip API.

Layout: 640x480, 1280-byte stride, 614400 bytes/frame. Little-endian 16-bit BGR565: red bits 4:0, green 10:5, blue 15:11; red=0x001f, green=0x07e0, blue=0xf800. Four consecutive pixels occupy one little-endian 64-bit word. Buffers at 0x81000000 and 0x81100000 are each 1 MiB apart and 64-byte aligned. Program/BSS/64 KiB stack remain in the first 2 MiB at 0x80000000; no framebuffer is included in the ELF or sent over UART. CPU initializes both as needed.

DMA is a coherent Get master on SBUS, before the broadcast coherence manager selected by WithBroadcastManager. Dirty Rocket cache lines are probed by the hub. `fence iorw, iorw` orders writes before device control; it is not presented as a cache flush. Do not move DMA to MBUS or remove coherence without adding a supported cache-clean/uncached mapping strategy. No unsupported custom flush instruction is used.

Actual revised register map at 0x10081000:

| Offset | Access | Meaning |
|---|---|---|
| 0x00 | read | bit 0: synchronized positive internal vsync status |
| 0x04 | read | bit 0: synchronized active-video underflow, clears during vsync |
| 0x08 | read/write | bit 0: enable, reset default 0 |
| 0x0c | read | bit 0: DMA busy; bit 1: video clock/reset ready; bit 2: DMA denied/corrupt sticky while enabled |
| 0x10 | 64-bit read/write | framebuffer physical base, default 0x81000000 |

Only change base after writing enable=0 and observing busy=0. Wait at least several pixel clocks before re-enabling (test waits 1000 system cycles). Base must be 64-byte aligned and entire frame must reside in mapped RAM [0x80000000,0xc0000000). DMA reads 64-byte bursts and wraps exactly at frame end. Tagged FIFO data recovers from missing pixels by discarding late words; future-frame words are held. Frame tags assume the queue cannot lag by 128 complete frames; reset/disable restores synchronization if that bound is exceeded.

Program/load sequence (operator supplies hardware identifiers):

1. Power the BoraLake board with its intended DDR module. Connect its HDMI output to a powered display and connect UART-TSI/JTAG to the host. Board clock is assumed 50 MHz at F22 per checkout metadata. No pin mapping was moved.
2. Build and validate the bitstream first. Do not use a partial/stale .bit after a failed make. Program it explicitly:

```
B=fpga/generated-src/chipyard.fpga.boralakeold.BoraLakeHarness.BoraLakeRocketConfig
vivado -mode batch -source scripts/program-boralake-hdmi.tcl \
  -tclargs '<HW_SERVER_HOST>:3121' '<CABLE_TARGET>' '<FPGA_DEVICE_NAME>' "$B/obj/BoraLakeHarness.bit"
```

The script requires exactly one selected cable/device and rejects a non-Kintex-160T device. It has not been run. Supply exact cable target/device names from Vivado Hardware Manager; do not select another board merely because it has a similar FPGA.

3. Allow DDR calibration/reset release; then load/run using the existing UART-TSI tool at the hardware's 921600 baud:

```
export LD_LIBRARY_PATH=/bwrcq/C/ansh_shah/ee194-lab1/.conda-env/riscv-tools/lib:${LD_LIBRARY_PATH:-}
generators/testchipip/uart_tsi/uart_tsi +tty='<UART_TSI_DEVICE>' +baudrate=921600 \
  +selfcheck +init_write=0x1000:0x80000000 +init_write=0x1004:0x0 software/hdmi-test/build/hdmi-test.elf
```

`<UART_TSI_DEVICE>` is the board's actual serial device (e.g. a `/dev/serial/by-id/...` path). Stop serial-console software using it. UART-TSI loads PT_LOAD bytes, applies the low/high boot-address writes, then its existing reset/start sequence writes hart 0 MSIP. BootROM clears MSIP and jumps via the boot-address register. Do not independently release hart before the load completes. No separate printf console is required. Leave uart_tsi running; this intentionally infinite test never writes a success exit to tohost.

Diagnostics: globals `hdmi_phase` (0 bars, 1 red, 2 green, 3 blue), `hdmi_frames_switched`, `hdmi_status`, `hdmi_trap_cause`, `hdmi_trap_pc` are readable through TSI. Locate their addresses using:

```
/bwrcq/C/ansh_shah/ee194-lab1/.conda-env/riscv-tools/bin/riscv64-unknown-elf-nm -n software/hdmi-test/build/hdmi-test.elf
```

Stop the polling host before opening a second UART-TSI process. An example status read without triggering a new hart start is:

```
generators/testchipip/uart_tsi/uart_tsi +tty='<UART_TSI_DEVICE>' +baudrate=921600 \
  +no_hart0_msip +init_read=0x1008100c none
```

Use `+init_read=0x<address-from-nm>` for a diagnostic global. Failures write tohost with exit codes 1 (trap), 2 (DMA stop timeout), 3 (CPU framebuffer readback), 4 (video not ready), or 5 (DMA response error). `hdmi_status` packs status at bits 63:32 and underflow at bit 0; it samples one instant, not a total event count.

If blank: check the build's timing/DRC result and target part first; check display input and cable, raw clock/PLL lock, reset and DDR calibration. Verify UART selfcheck/load succeeds, enable is 1, base is one of the two buffers, status ready is 1 and busy changes. Check trap/phase globals. Underflow indicates a DMA/DDR/coherence/bandwidth problem; a stable image with swapped colors suggests incorrect format. Source/device pin checks do not substitute for schematic or physical HDMI validation.
