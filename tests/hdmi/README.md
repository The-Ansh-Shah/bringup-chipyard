# Scanout RTL test

First generate the normal BoraLake FPGA Verilog, then run `python tests/hdmi/run.py` from the worktree root. Set `VERILATOR` to override the installed tool path. The runner discovers the emitted controller's module dependencies and compiles only that hierarchy with the testbench. No Vivado timing/DRC check is weakened.

The test drives the actual TL register interface, a burst-response memory model and separate 100 MHz / approximately 25.1786 MHz clocks with walking relative phase. It checks: no DMA while disabled; aligned in-range 64-byte reads; a complete 307200-pixel bar frame; four distinct pixel lanes; frame wrap; disabling/draining an outstanding response; changing framebuffer base; forced active-video underflow and recovery by the next complete frame; and PLL/system reset followed by explicit software restart. Any mismatch, wrong pixel count, missing underflow indication or timeout fails the simulation. The transmitter is a stub: serializers, DDR calibration, cache coherence, board constraints and physical HDMI are outside this test.

The current controller emitter name is ClockSinkDomain_2 for this exact config. If a later integration changes module naming or bus port pruning, adapt the testbench ports against the generated HDL rather than testing a copied implementation.

For repeatable latency, contention, FIFO-occupancy and useful-pixel profiling of the unchanged controller, see [profile/README.md](profile/README.md). The saved baseline and comparison tool support measuring subsequent DMA pipelining changes.
