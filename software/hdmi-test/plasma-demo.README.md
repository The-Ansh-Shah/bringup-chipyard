# Full-frame plasma stress demo

`hdmi-plasma.elf` animates overlapping sine fields and concentric waves through a smoothly cycling RGB palette. The upper-left HUD says PLASMA and shows an eight-digit hexadecimal animation-frame counter. All math is integer; no assets, floating point, libm, allocation or console are required.

The procedural field is computed at 160x120 and expanded 4x4 into the actual 640x480 BGR565 framebuffer. Every iteration writes all 614400 framebuffer bytes using aligned 64-bit stores, then adds the HUD. This is a substantially heavier memory/coherence workload than the smiley's dirty rectangles. At 60 animation frames/s these CPU stores alone would be 36.864 MB/s, in addition to scanout reads and coherence traffic. Block expansion trades procedural CPU work for framebuffer bandwidth.

The program stops/drains scanout, selects eight outstanding DMA slots at register +0x18, initializes both buffers, then enables once. Each frame renders only into the inactive buffer, fences stores, submits a page flip and waits for acknowledgement before reusing the old front buffer. It requires the matching new pipelined/page-flip bitstream, not rc1. Program/stack remain near 0x80000000 and buffers at 0x81000000/0x81100000.

The renderer targets one new image per approximately 59.95 Hz refresh but is **not physically benchmarked**. Slow rendering safely repeats the old frame. Shared `hdmi_*` cadence/render counters match read-flip-stats.py. `hdmi_slow_present_intervals` counts presentation gaps over 25 ms, not an exact number of dropped frames. Counter changes are published within the short equal-even `hdmi_stats_seq` snapshot protocol.

Use this checkout's standard UART-TSI loading command at 921600 baud, with `hdmi-plasma.elf` as its final argument. Reprogram/reset between demos. No board programming or physical HDMI validation was performed by the agent.

Native validation: `python tests/hdmi/pageflip/test_plasma.py`. It executes the actual C renderer under AddressSanitizer and UBSan over 300 frames, compares each expanded pixel against the procedural reference, checks the other buffer is unchanged, verifies deterministic rendering and changing frame hashes. Pass an optional `.ppm` output filename to save frame 99 as a CPU-rendered preview; this is not a board capture.
