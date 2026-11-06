# Bouncing smiley HDMI demo

`animated-demo.c` draws a dark checker/grid background, a yellow/orange 64x64 smiley that bounces inside the play area and periodically blinks, and a large eight-digit frame counter. Static labels identify Rocket HDMI, the target cadence and BGR565 format. The frame counter is an animation-frame count, not an FPS claim.

The program requires the new continuous-timing/page-flip bitstream. It will not run correctly with the old v0.1-rc1 hardware, which lacks the page-flip registers. The agent has not run this on a physical board.

Both framebuffer backgrounds are initialized once at 0x81000000 and 0x81100000. After enabling scanout once, each update restores only the inactive buffer's own previous sprite rectangle and counter rectangle, paints the next sprite/counter, fences, submits the new base through `flip_to`, and waits for its presentation acknowledgement before reusing the old front buffer. Sprite history is tracked independently for each buffer, preventing two-frame-old trails. Two precomputed sprite variants occupy 16 KiB of BSS. No allocation, console, full-frame per-update redraw, or unsupported cache flush is used.

At roughly 60 presented frames/s, motion advances four pixels horizontally and two vertically per frame; the eyes close for eight frames every 120 frames. If rendering is slower, motion/blinking slows with the actual presentation rate while hardware repeats frames safely. `TARGET 60 HZ` is a target label, not a measured result.

## Compile and load

The parent worktree's Makefile can build this using the same common CFLAGS/startup/linker as the other tests. The direct build used was (from software/hdmi-test, with CROSS set to the installed RISC-V prefix):

```sh
"${CROSS:-riscv64-unknown-elf-}gcc" -O2 -g -Wall -Wextra -Werror \
  -march=rv64imac_zicsr_zifencei -mabi=lp64 -mcmodel=medany \
  -msmall-data-limit=0 -ffreestanding -fno-builtin -fno-pie \
  -nostdlib -nostartfiles -static \
  -Wl,--no-relax,-T,link.ld,-Map,build/hdmi-animated-demo.map \
  -o build/hdmi-animated-demo.elf start.S animated-demo.c
```

Use the normal documented 921600-baud UART-TSI boot-address/load command with `build/hdmi-animated-demo.elf` as its final argument. Program the matched new bitstream first for a clean start. No console UART is required; leave the UART-TSI loader running until a separate diagnostic read is needed.

## Metrics

The common read-flip-stats helper works with the matching animated-demo ELF. `hdmi_stats_seq` is odd only around a short post-acknowledgement update, not while rendering or waiting for a flip. Accept equal even sequence values before/after reading the cadence fields.

- `hdmi_frames_switched`, `hdmi_elapsed_cycles`, `hdmi_interval_cycles_last/max`: acknowledged presentation cadence. At the 100 MHz system clock, average FPS is `(frames_switched - 1) * 100000000 / elapsed_cycles`, once at least two flips have completed.
- `hdmi_render_cycles_last/max/total`: dirty-rectangle rendering time, including the pre-submit memory fence; excludes page-flip waiting and one-time initialization.
- `hdmi_slow_present_intervals`: intervals longer than 25 ms, a diagnostic threshold between one and two nominal display periods. It is not an exact dropped-frame count.
- `hdmi_sprite_x/y`, `hdmi_phase`: acknowledged position and eyelid state (0 open, 1 closed).
- `hdmi_status`: common DMA/clock-ready status in bits 63:32 and underflow-ever at bit 0.

Common `hdmi_flip_wait_cycles_last/max` are updated by flip_to separately and are not part of the animation's cadence snapshot. Trap/failure diagnostics and HTIF exit codes remain those of main.c.

## Validation completed

GCC 13.2.0 built successfully with warnings treated as errors; the only linker warning is the existing intentional RWX RAM LOAD segment. Entry is 0x80000000. PT_LOAD is 2952 bytes; BSS plus 64 KiB stack is NOLOAD/PT_NULL. Stack top is 0x80014c20, inside the first 2 MiB and far below either framebuffer.

A temporary native test compiled the actual C painters with mock MMIO/timing and compared 500 dirty-rectangle frames byte-for-byte against independently positioned full redraws. It also checked that the active front buffer stayed unchanged between flips. It passed, covering multiple horizontal/vertical bounces, four blink cycles, buffer alternation and counter digit transitions. This validates rendering/state bookkeeping, not RISC-V execution speed, actual DDR/coherence, HDMI encoding or physical display output. No achieved 60 FPS claim is made before hardware measurement.
