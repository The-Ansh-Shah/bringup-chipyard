/* Pre-render two distinct images, then request one visible flip per frame.
 * Measures presentation cadence; intentionally excludes full-frame rendering cost. */
#define HDMI_PAGEFLIP 1
#define HDMI_NO_MAIN 1
#include "main.c"
#ifndef HDMI_DMA_LIMIT
#define HDMI_DMA_LIMIT 4
#endif
#if HDMI_DMA_LIMIT < 1 || HDMI_DMA_LIMIT > 8
#error "HDMI_DMA_LIMIT must be 1..8"
#endif
volatile uint64_t hdmi_first_flip_cycle, hdmi_elapsed_cycles;
volatile uint64_t hdmi_stats_seq;
volatile uint64_t hdmi_interval_cycles_last, hdmi_interval_cycles_max;
int main(void) {
 stop();
 REG32(0x18)=HDMI_DMA_LIMIT; fence();
 fill(FRAME0, 0); fill(FRAME1, 1);
 BASE = FRAME0; fence();
 if (!(REG32(0x0c) & 2)) fail(4);
 REG32(0x08) = 1; fence();
 uintptr_t next = FRAME1;
 uint64_t previous = 0;
 for (;;) {
  flip_to(next);
  uint64_t now = cycles();
  hdmi_stats_seq++; fence();
  if (!previous) hdmi_first_flip_cycle = now;
  else {
   hdmi_interval_cycles_last = now - previous;
   if (hdmi_interval_cycles_last > hdmi_interval_cycles_max)
    hdmi_interval_cycles_max = hdmi_interval_cycles_last;
  }
  previous = now;
  hdmi_elapsed_cycles = now - hdmi_first_flip_cycle;
  hdmi_frames_switched++;
  hdmi_phase = next == FRAME0 ? 0 : 1;
  hdmi_status = ((uint64_t)REG32(0x0c) << 32) | REG32(0x40);
  fence(); hdmi_stats_seq++; fence();
  if (REG32(0x0c) & 4) fail(5);
  next = next == FRAME0 ? FRAME1 : FRAME0;
 }
}
