# v0.2 procedural renderer validation

All ELFs compiled using GCC13.2 RV64IMAC_zicsr_zifencei, -O2 and the shared freestanding startup/linker. Only the known single RWX load-segment warning occurred.

- Donut: 256 orientations, ASan/UBSan, guarded framebuffer, changing output and nearest-depth invariance under reversed sample order.
- Plasma: 300 full frames, ASan/UBSan, reference procedural pixels/block expansion, front unchanged, deterministic and299changing adjacent frames.
- Tunnel: 32 frames, UBSan, reference procedural pixels, changing/deterministic images, front unchanged, gap guards and DMA limit8.
- Smiley: 500dirty updates compared to full redraw, front unchanged, bounce/blink/digits exercised.

These host-native tests exercise actual C painters with mocked device/clock/flip functions. They do not execute Rocket instruction timing or validate physical HDMI. All new renderers select eight DMA slots while stopped, render only the inactive buffer and wait for acknowledged flips. Target60FPS remains unmeasured. The eight-slot flip-stress program uses pre-rendered images, isolating presentation from CPU rendering.

Rebuild: make -C software/hdmi-test demos hold
Tests: python tests/hdmi/pageflip/test_{donut,plasma,tunnel,animation}.py (run each script individually).
