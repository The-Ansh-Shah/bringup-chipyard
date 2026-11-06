/* Full-frame plasma: fixed point, no assets, no floating point. */
#define HDMI_PAGEFLIP 1
#define HDMI_NO_MAIN 1
#include "main.c"
#define PW 160u
#define PH 120u
volatile uint64_t hdmi_stats_seq,hdmi_first_flip_cycle,hdmi_elapsed_cycles;
volatile uint64_t hdmi_interval_cycles_last,hdmi_interval_cycles_max;
volatile uint64_t hdmi_render_cycles_last,hdmi_render_cycles_max;
volatile uint64_t hdmi_render_cycles_total,hdmi_slow_present_intervals;
static const int16_t wave[256]={
 0,3,6,9,12,16,19,22,25,28,31,34,37,40,43,46,
 49,51,54,57,60,63,65,68,71,73,76,78,81,83,85,88,
 90,92,94,96,98,100,102,104,106,107,109,111,112,113,115,116,
 117,118,120,121,122,122,123,124,125,125,126,126,126,127,127,127,
 127,127,127,127,126,126,126,125,125,124,123,122,122,121,120,118,
 117,116,115,113,112,111,109,107,106,104,102,100,98,96,94,92,
 90,88,85,83,81,78,76,73,71,68,65,63,60,57,54,51,
 49,46,43,40,37,34,31,28,25,22,19,16,12,9,6,3,
 0,-3,-6,-9,-12,-16,-19,-22,-25,-28,-31,-34,-37,-40,-43,-46,
 -49,-51,-54,-57,-60,-63,-65,-68,-71,-73,-76,-78,-81,-83,-85,-88,
 -90,-92,-94,-96,-98,-100,-102,-104,-106,-107,-109,-111,-112,-113,-115,-116,
 -117,-118,-120,-121,-122,-122,-123,-124,-125,-125,-126,-126,-126,-127,-127,-127,
 -127,-127,-127,-127,-126,-126,-126,-125,-125,-124,-123,-122,-122,-121,-120,-118,
 -117,-116,-115,-113,-112,-111,-109,-107,-106,-104,-102,-100,-98,-96,-94,-92,
 -90,-88,-85,-83,-81,-78,-76,-73,-71,-68,-65,-63,-60,-57,-54,-51,
 -49,-46,-43,-40,-37,-34,-31,-28,-25,-22,-19,-16,-12,-9,-6,-3,
};
static uint16_t palette[256];
static uint8_t radius_map[PH][PW];
static unsigned root(unsigned n){
 unsigned x=0,bit=1u<<30;
 while(bit>n)bit>>=2;
 while(bit){if(n>=x+bit){n-=x+bit;x=(x>>1)+bit;}else x>>=1;bit>>=2;}
 return x;
}
static uint16_t color(unsigned r,unsigned g,unsigned b){
 return (uint16_t)(((b>>3)<<11)|((g>>2)<<5)|(r>>3));
}
static void prepare(void){
 for(unsigned i=0;i<256;i++)
  palette[i]=color((unsigned)(128+wave[i]),(unsigned)(128+wave[(i+85)&255]),
                   (unsigned)(128+wave[(i+170)&255]));
 for(unsigned y=0;y<PH;y++)for(unsigned x=0;x<PW;x++){
  int dx=(int)x-80,dy=(int)y-60;
  radius_map[y][x]=(uint8_t)(root((unsigned)(dx*dx+dy*dy)*16u)*2u);
 }
}
/* Every computed color expands to 4x4; every screen pixel is rewritten.
 * Four 64-bit stores per sample; aligned BGR565 lanes. */
static void render(uintptr_t base,uint32_t frame){
 int16_t xs[PW],ys[PH];unsigned t=frame&255;
 for(unsigned x=0;x<PW;x++)xs[x]=wave[(x*3+t)&255];
 for(unsigned y=0;y<PH;y++)ys[y]=wave[(y*4+256-t)&255];
 for(unsigned y=0;y<PH;y++){
  volatile uint64_t *row=(volatile uint64_t *)(base+y*4*640*2);
  for(unsigned x=0;x<PW;x++){
   int v=xs[x]+ys[y]+wave[(radius_map[y][x]+2*t)&255];
   unsigned index=(unsigned)((v+384)/3+t)&255;
   uint64_t packed=(uint64_t)palette[index]*UINT64_C(0x0001000100010001);
   row[x]=packed;row[x+160]=packed;row[x+320]=packed;row[x+480]=packed;
  }
 }
}
static const uint8_t title[6][7]={
 {30,17,17,30,16,16,16},{16,16,16,16,16,16,31},
 {14,17,17,31,17,17,17},{15,16,16,14,1,1,30},
 {17,27,21,21,17,17,17},{14,17,17,31,17,17,17}};
static const uint8_t digit[16][7]={
 {14,17,19,21,25,17,14},{4,12,4,4,4,4,14},{14,17,1,2,4,8,31},
 {30,1,1,14,1,1,30},{2,6,10,18,31,2,2},{31,16,16,30,1,1,30},
 {14,16,16,30,17,17,14},{31,1,2,4,8,8,8},{14,17,17,14,17,17,14},
 {14,17,17,15,1,1,14},{14,17,17,31,17,17,17},{30,17,17,30,17,17,30},
 {14,17,16,16,16,17,14},{30,17,17,17,17,17,30},{31,16,16,30,16,16,31},
 {31,16,16,30,16,16,16}};
static void glyph(uintptr_t base,unsigned x,const uint8_t *g){
 volatile uint16_t *p=(volatile uint16_t *)base;
 for(unsigned y=0;y<7;y++)for(unsigned c=0;c<5;c++)if(g[y]&(16u>>c))
  for(unsigned dy=0;dy<3;dy++)for(unsigned dx=0;dx<3;dx++)
   p[(10+y*3+dy)*640+x+c*3+dx]=0xffff;
}
static void hud(uintptr_t base,uint32_t frame){
 volatile uint64_t *p=(volatile uint64_t *)base;
 for(unsigned y=4;y<38;y++)for(unsigned x=2;x<92;x++)p[y*160+x]=0;
 for(unsigned n=0;n<6;n++)glyph(base,16+n*18,title[n]);
 for(unsigned n=0;n<8;n++)glyph(base,176+n*18,digit[(frame>>((7-n)*4))&15]);
}
int main(void){
 (void)fill;stop();REG32(0x18)=8;fence();prepare();
 render(FRAME0,0);hud(FRAME0,0);render(FRAME1,0);hud(FRAME1,0);
 fence();BASE=FRAME0;fence();if(!(REG32(0x0c)&2))fail(4);
 REG32(0x08)=1;fence();unsigned next=1;uint64_t previous=0;
 for(uint32_t frame=1;;frame++){
  uintptr_t base=next?FRAME1:FRAME0;
  uint64_t begin=cycles();render(base,frame);hud(base,frame);fence();
  uint64_t elapsed=cycles()-begin;flip_to(base);uint64_t now=cycles();
  hdmi_stats_seq++;fence();
  if(!previous)hdmi_first_flip_cycle=now;
  else{
   hdmi_interval_cycles_last=now-previous;
   if(hdmi_interval_cycles_last>hdmi_interval_cycles_max)
    hdmi_interval_cycles_max=hdmi_interval_cycles_last;
   if(hdmi_interval_cycles_last>2500000)hdmi_slow_present_intervals++;
  }
  previous=now;hdmi_elapsed_cycles=now-hdmi_first_flip_cycle;
  hdmi_render_cycles_last=elapsed;hdmi_render_cycles_total+=elapsed;
  if(elapsed>hdmi_render_cycles_max)hdmi_render_cycles_max=elapsed;
  hdmi_frames_switched++;hdmi_phase=frame;
  hdmi_status=((uint64_t)REG32(0x0c)<<32)|REG32(0x40);
  fence();hdmi_stats_seq++;fence();if(REG32(0x0c)&4)fail(5);next^=1;
 }
}
