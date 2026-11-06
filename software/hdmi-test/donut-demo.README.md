# Rocket ASCII torus demo

`hdmi-donut.elf` renders a rotating 3D torus as shaded colored ASCII-like glyphs. The torus changes orientation about two axes; cyan/purple lighting follows rotated surface normals, and a depth buffer selects the nearest surface. It is an original fixed-point implementation inspired by the classic spinning ASCII donut effect, without copied donut.c code or embedded video assets.

The renderer samples 4096 surface points per frame, projects them onto an 80x48 character grid, and draws 8x8 glyphs into a 640x384 region of the 640x480 BGR565 framebuffer. Header/footer identify the test and target cadence. Packed 64-bit glyph masks write four pixels per store. It uses integer Q14 lookup-table rotations, integer perspective division and no floating point, libm or dynamic allocation. Geometry, shade/depth buffers and stack reside in the normal linked 2 MiB program region, separate from framebuffer regions at 0x81000000 / 0x81100000.

## Build and load

From the repository root:

```sh
make -C software/hdmi-test build/hdmi-donut.elf
```

Use the matching v0.2 page-flip bitstream, not v0.1-rc1. Reset/reprogram before loading another ELF. Follow the release's UART-TSI command with `hdmi-donut.elf` as its final argument; UART-TSI remains at 921600 baud. This program selects DMA limit 8 through register +0x18 while stopped. The bitstream's reset default remains four.

The program renders into the inactive buffer, fences, submits a flip and waits for visible presentation acknowledgement before reusing the old front buffer. Software enable remains asserted during animation, preserving HDMI timing. Rendering is unpaced: it aims for one new image per approximately 59.95 Hz video frame. If CPU rendering misses that deadline, hardware repeats the last complete image. **60 FPS is a target, not a measured physical result.** No board was programmed or display observed by the agent.

## Measure

`read-flip-stats.py --elf software/hdmi-test/build/hdmi-donut.elf ...` reads the same sequence-guarded cadence fields as the flip-stress test. Additional symbols:

- `hdmi_render_cycles_last/max/total`: render time including the pre-submit fence, excluding page-flip wait and initialization.
- `hdmi_slow_present_intervals`: acknowledged intervals above 25 ms; a diagnostic, not an exact dropped-frame count.
- `hdmi_donut_samples`: number of depth-buffer replacements in the current render; not the total fixed 4096 submitted samples.
- `hdmi_donut_visible_cells`: occupied character cells in the current render.
- `hdmi_phase`: low eight bits of the last acknowledged animation-frame index.

The donut sample/cell counters update during render and are not protected by the cadence sequence guard; use only as live approximate diagnostics. Standard elapsed-time and acknowledged-frame counters use the guard. At 100 MHz, average presentation FPS is `(frames_switched - 1) * 100000000 / elapsed_cycles` after at least two acknowledgements.

## Native validation

```sh
python tests/hdmi/pageflip/test_donut.py --preview hdmi-validation/donut-preview.ppm
```

The test runs the actual C painter for 256 orientations with AddressSanitizer and UndefinedBehaviorSanitizer. It checks framebuffer canaries, animation changes, bounded shade/depth values, nonempty silhouettes, and nearest-depth invariance when all surface samples are visited in reverse order. It optionally writes an actual framebuffer PPM preview. This validates software math and memory behavior on the host, not Rocket performance, cache/coherence behavior or HDMI electrical output.
