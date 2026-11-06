/* Dirty-rectangle animation for the continuous-timing/page-flip RTL.
 * Actual rendering and presentation cadence require a physical-board run. */
#define HDMI_PAGEFLIP 1
#define HDMI_NO_MAIN 1
#include "main.c"
#define WIDTH 640
#define HEIGHT 480
#define SPRITE 64
#define TRANSPARENT UINT16_C(0xf81f)

volatile uint64_t hdmi_stats_seq, hdmi_first_flip_cycle, hdmi_elapsed_cycles;
volatile uint64_t hdmi_interval_cycles_last, hdmi_interval_cycles_max;
volatile uint64_t hdmi_render_cycles_last, hdmi_render_cycles_max;
volatile uint64_t hdmi_render_cycles_total, hdmi_slow_present_intervals;
volatile uint64_t hdmi_sprite_x, hdmi_sprite_y;
static uint16_t faces[2][SPRITE][SPRITE];
/* Five-bit rows, seven rows/glyph; digits then A-Z. */
static const uint8_t font[36][7] = {
 {14,17,19,21,25,17,14},{4,12,4,4,4,4,14},{14,17,1,2,4,8,31},
 {30,1,1,14,1,1,30},{2,6,10,18,31,2,2},{31,16,16,30,1,1,30},
 {14,16,16,30,17,17,14},{31,1,2,4,8,8,8},{14,17,17,14,17,17,14},
 {14,17,17,15,1,1,14},
 {14,17,17,31,17,17,17},{30,17,17,30,17,17,30},
 {14,17,16,16,16,17,14},{30,17,17,17,17,17,30},
 {31,16,16,30,16,16,31},{31,16,16,30,16,16,16},
 {14,17,16,23,17,17,15},{17,17,17,31,17,17,17},
 {14,4,4,4,4,4,14},{7,2,2,2,18,18,12},
 {17,18,20,24,20,18,17},{16,16,16,16,16,16,31},
 {17,27,21,21,17,17,17},{17,25,21,19,17,17,17},
 {14,17,17,17,17,17,14},{30,17,17,30,16,16,16},
 {14,17,17,17,21,18,13},{30,17,17,30,20,18,17},
 {15,16,16,14,1,1,30},{31,4,4,4,4,4,4},
 {17,17,17,17,17,17,14},{17,17,17,17,17,10,4},
 {17,17,17,21,21,21,10},{17,17,10,4,10,17,17},
 {17,17,10,4,4,4,4},{31,1,2,4,8,16,31}
};
static uint16_t bgr565(unsigned r, unsigned g, unsigned b) {
 return (uint16_t)(((b >> 3) << 11) | ((g >> 2) << 5) | (r >> 3));
}
static uint16_t background(unsigned x, unsigned y) {
 if (y >= 108 && y < 112) return bgr565(20,200,220);
 if (y < 112) return bgr565(8,18+(y>>3),38+(x>>5));
 if (y >= 464) return bgr565(8,18,38);
 if ((x & 31) == 0 || (y & 31) == 0) return bgr565(30,55,82);
 unsigned tile = ((x>>5) ^ (y>>5)) & 1;
 return bgr565(10+tile*4,24+(y>>5),42+(x>>6)+tile*9);
}
/* x and width are multiples of four, so each store is 8-byte aligned. */
static void restore(uintptr_t base, unsigned x, unsigned y, unsigned w, unsigned h) {
 for (unsigned row=y; row<y+h; ++row) {
  volatile uint64_t *dst=(volatile uint64_t *)(base+2*(row*WIDTH+x));
  for (unsigned col=x; col<x+w; col+=4) {
   uint64_t packed=background(col,row);
   packed|=(uint64_t)background(col+1,row)<<16;
   packed|=(uint64_t)background(col+2,row)<<32;
   packed|=(uint64_t)background(col+3,row)<<48;
   *dst++=packed;
  }
 }
}
static void text(uintptr_t base, unsigned x, unsigned y, const char *s,
                 unsigned scale, uint16_t color) {
 volatile uint16_t *dst=(volatile uint16_t *)base;
 while (*s) {
  unsigned char c=(unsigned char)*s++;
  int glyph=c>='0' && c<='9' ? (int)(c-'0') :
            c>='A' && c<='Z' ? (int)(c-'A')+10 : -1;
  if (glyph>=0)
   for (unsigned row=0; row<7; ++row)
    for (unsigned col=0; col<5; ++col)
     if (font[glyph][row] & (1u<<(4-col)))
      for (unsigned dy=0; dy<scale; ++dy)
       for (unsigned dx=0; dx<scale; ++dx)
        dst[(y+row*scale+dy)*WIDTH+x+col*scale+dx]=color;
  x+=6*scale;
 }
}
static void counter(uintptr_t base, uint64_t frame) {
 char digits[9];
 uint32_t value=(uint32_t)(frame%UINT64_C(100000000));
 for (int i=7; i>=0; --i) { digits[i]=(char)('0'+value%10); value/=10; }
 digits[8]=0;
 restore(base,140,54,164,30);
 text(base,144,58,digits,3,bgr565(120,245,225));
}
static void prepare_faces(void) {
 for (unsigned blink=0; blink<2; ++blink)
  for (int y=0; y<SPRITE; ++y)
   for (int x=0; x<SPRITE; ++x) {
    int dx=x-31,dy=y-31,radius=dx*dx+dy*dy;
    uint16_t c=TRANSPARENT;
    if (radius<=29*29) {
     c=radius>27*27 ? bgr565(180,100,10) : bgr565(255,212,48);
     int hx=x-22,hy=y-20;
     if (hx*hx+hy*hy<9*9) c=bgr565(255,235,104);
     for (int eye=21; eye<=43; eye+=22) {
      int ex=x-eye,ey=y-26;
      if ((!blink && ex*ex*25+ey*ey*9<=225) ||
          (blink && ex>=-5 && ex<=5 && ey>=-1 && ey<=1)) c=0;
     }
     if (y>=38 && radius>=17*17 && radius<=20*20) c=0;
    }
    faces[blink][y][x]=c;
   }
}
static void smiley(uintptr_t base, unsigned x, unsigned y, unsigned blink) {
 for (unsigned row=0; row<SPRITE; ++row) {
  volatile uint64_t *dst=(volatile uint64_t *)(base+2*((y+row)*WIDTH+x));
  for (unsigned col=0; col<SPRITE; col+=4) {
   uint64_t packed=*dst;
   for (unsigned lane=0; lane<4; ++lane) {
    uint16_t c=faces[blink][row][col+lane];
    if (c!=TRANSPARENT) {
     packed&=~(UINT64_C(0xffff)<<(lane*16));
     packed|=(uint64_t)c<<(lane*16);
    }
   }
   *dst++=packed;
  }
 }
}
static void initialize(uintptr_t base) {
 restore(base,0,0,WIDTH,HEIGHT);
 text(base,24,18,"ROCKET HDMI",3,bgr565(240,245,255));
 text(base,24,58,"FRAME",3,bgr565(150,175,200));
 text(base,24,90,"PAGE FLIPS   TARGET 60 HZ",1,bgr565(150,175,200));
 text(base,24,468,"BGR565   DIRTY RECTANGLES   BLINKING SMILEY",1,bgr565(150,175,200));
 counter(base,0);
}
int main(void) {
 (void)fill; /* Included bars helper is deliberately unused at runtime. */
 stop(); prepare_faces();
 initialize(FRAME0); initialize(FRAME1);
 int x=64,y=160,vx=4,vy=2;
 unsigned old_x[2]={64,0},old_y[2]={160,0},has_sprite[2]={1,0};
 smiley(FRAME0,(unsigned)x,(unsigned)y,0);
 fence(); BASE=FRAME0; fence();
 if (!(REG32(0x0c)&2)) fail(4);
 REG32(0x08)=1; fence();
 unsigned next=1;
 uint64_t previous=0;
 for (uint64_t frame=1;; ++frame) {
  if (x+vx<16 || x+vx>WIDTH-16-SPRITE) vx=-vx;
  if (y+vy<128 || y+vy>HEIGHT-16-SPRITE) vy=-vy;
  x+=vx; y+=vy;
  unsigned blink=frame%120>=112;
  uintptr_t base=next ? FRAME1 : FRAME0;
  uint64_t render_start=cycles();
  if (has_sprite[next]) restore(base,old_x[next],old_y[next],SPRITE,SPRITE);
  smiley(base,(unsigned)x,(unsigned)y,blink);
  counter(base,frame); fence();
  uint64_t render_cycles=cycles()-render_start;
  old_x[next]=(unsigned)x; old_y[next]=(unsigned)y; has_sprite[next]=1;
  flip_to(base); /* The old front buffer becomes reusable only after ack. */
  uint64_t now=cycles();
  hdmi_stats_seq++; fence();
  if (!previous) hdmi_first_flip_cycle=now;
  else {
   hdmi_interval_cycles_last=now-previous;
   if (hdmi_interval_cycles_last>hdmi_interval_cycles_max)
    hdmi_interval_cycles_max=hdmi_interval_cycles_last;
   /* 25 ms is between one and two nominal ~16.68 ms display periods. */
   if (hdmi_interval_cycles_last>2500000) hdmi_slow_present_intervals++;
  }
  previous=now; hdmi_elapsed_cycles=now-hdmi_first_flip_cycle;
  hdmi_render_cycles_last=render_cycles;
  if (render_cycles>hdmi_render_cycles_max) hdmi_render_cycles_max=render_cycles;
  hdmi_render_cycles_total+=render_cycles; hdmi_frames_switched++;
  hdmi_sprite_x=(unsigned)x; hdmi_sprite_y=(unsigned)y; hdmi_phase=blink;
  hdmi_status=((uint64_t)REG32(0x0c)<<32)|REG32(0x40);
  fence(); hdmi_stats_seq++; fence();
  if (REG32(0x0c)&4) fail(5);
  next^=1;
 }
}
