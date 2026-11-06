#include <stdint.h>
/* Diagnostic builds hold one image, avoiding periodic transmitter resets. */
#ifndef HDMI_HOLD_PHASE
#define HDMI_HOLD_PHASE (-1)
#endif
#if HDMI_HOLD_PHASE < -1 || HDMI_HOLD_PHASE > 3
#error "HDMI_HOLD_PHASE must be -1 (cycle), 0 (bars), 1 (red), 2 (green), or 3 (blue)"
#endif
#ifndef HDMI_PAGEFLIP
#define HDMI_PAGEFLIP 0
#endif
#define VIDEO ((uintptr_t)0x10081000)
#define REG32(off) (*(volatile uint32_t *)(VIDEO + (off)))
#define BASE (*(volatile uint64_t *)(VIDEO + 0x10))
#define FRAME0 ((uintptr_t)0x81000000)
#define FRAME1 ((uintptr_t)0x81100000)
#define WORDS (640 * 480 / 4)
/* No console UART exists in this config. UART-TSI polls HTIF tohost. */
volatile uint64_t tohost __attribute__((section(".htif"), aligned(64))) = 0;
volatile uint64_t fromhost __attribute__((section(".htif"), aligned(64))) = 0;
volatile uint64_t hdmi_phase, hdmi_frames_switched, hdmi_status;
volatile uint64_t hdmi_trap_cause, hdmi_trap_pc;
static inline uint64_t cycles(void) { uint64_t n; __asm__ volatile("rdcycle %0" : "=r"(n)); return n; }
static inline void fence(void) { __asm__ volatile("fence iorw, iorw" ::: "memory"); }
static void delay(uint64_t n) { uint64_t t = cycles(); while (cycles() - t < n) {} }
static void fail(unsigned n) { tohost = ((uint64_t)n << 1) | 1; fence(); for (;;) {} }
static void stop(void) {
 REG32(0x08) = 0; fence();
 uint64_t start = cycles();
 while (REG32(0x0c) & 1) if (cycles() - start > 100000000) fail(2);
 /* Allow both FIFO domains to observe asserted reset before restarting. */
 delay(1000);
}
static void fill(uintptr_t addr, unsigned phase) {
 /* Controller's red is bits 4:0, green 10:5, blue 15:11 (BGR565). */
 static const uint16_t bars[8] = {0xffff, 0x07ff, 0xffe0, 0x07e0, 0xf81f, 0x001f, 0xf800, 0x0000};
 volatile uint64_t *p = (volatile uint64_t *)addr;
 for (unsigned i = 0; i < WORDS; ++i) {
  uint16_t c = phase == 0 ? bars[((i % 160) * 4) / 80] : phase == 1 ? 0x001f : phase == 2 ? 0x07e0 : 0xf800;
  p[i] = (uint64_t)c * UINT64_C(0x0001000100010001);
 }
 /* DMA enters the coherent SBUS before the broadcast hub: dirty Rocket lines
    are probed. A fence orders stores; no unsupported cache-flush opcode used. */
 fence();
 uint16_t first = phase == 0 ? bars[0] : phase == 1 ? 0x001f : phase == 2 ? 0x07e0 : 0xf800;
 if (p[0] != (uint64_t)first * UINT64_C(0x0001000100010001)) fail(3);
}
#if HDMI_PAGEFLIP
#define NEXT_BASE (*(volatile uint64_t *)(VIDEO + 0x20))
volatile uint64_t hdmi_flip_wait_cycles_last, hdmi_flip_wait_cycles_max;
static void flip_to(uintptr_t addr) {
 uint32_t before;
 uint64_t start = cycles();
 while (REG32(0x28) & 1) if (cycles() - start > 20000000) fail(6);
 before = REG32(0x30);
 NEXT_BASE = addr; fence();
 REG32(0x28) = 1; fence();
 while (REG32(0x30) == before) {
  if (REG32(0x28) & 4) fail(7);
  if (cycles() - start > 20000000) fail(8);
 }
 hdmi_flip_wait_cycles_last = cycles() - start;
 if (hdmi_flip_wait_cycles_last > hdmi_flip_wait_cycles_max)
  hdmi_flip_wait_cycles_max = hdmi_flip_wait_cycles_last;
}
#endif
#ifndef HDMI_NO_MAIN
int main(void) {
 stop();
 uintptr_t next = FRAME0;
#if HDMI_PAGEFLIP
 unsigned started = 0;
#endif
 for (unsigned phase = HDMI_HOLD_PHASE < 0 ? 0 : HDMI_HOLD_PHASE;; phase = (phase + 1) & 3) {
  fill(next, phase); /* writes inactive buffer while the previous one is displayed */
#if HDMI_PAGEFLIP
  if (started) { flip_to(next); }
  else {
#endif
   stop();
   BASE = next; fence();
   if (!(REG32(0x0c) & 2)) fail(4);
   REG32(0x08) = 1; fence();
#if HDMI_PAGEFLIP
   started = 1;
  }
#endif
  hdmi_phase = phase; hdmi_frames_switched++;
#if HDMI_HOLD_PHASE >= 0
  /* Enable exactly once. The timing/transmitter should remain running. */
  for (;;) {
   hdmi_status = ((uint64_t)REG32(0x0c) << 32) | REG32(0x04);
   if (REG32(0x0c) & 4) fail(5);
   delay(UINT64_C(1000000));
  }
#endif
  next = next == FRAME0 ? FRAME1 : FRAME0;
  delay(UINT64_C(200000000)); /* 2 seconds at 100 MHz */
  hdmi_status = ((uint64_t)REG32(0x0c) << 32) | REG32(0x04);
  if (REG32(0x0c) & 4) fail(5);
 }
}

#endif /* HDMI_NO_MAIN */
