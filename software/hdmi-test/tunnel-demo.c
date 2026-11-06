/* Fixed-point neon tunnel. No floating point, libc, or dynamic allocation. */
#define HDMI_PAGEFLIP 1
#define HDMI_NO_MAIN 1
#include "main.c"
#define TW 160
#define TH 120
#define PIXELS (640*480)
static uint8_t angles[TW*TH], lights[TW*TH];
static uint16_t depths[TW*TH];
static uint64_t palette[8][256];
volatile uint64_t hdmi_stats_seq,hdmi_first_flip_cycle,hdmi_elapsed_cycles;
volatile uint64_t hdmi_interval_cycles_last,hdmi_interval_cycles_max;
volatile uint64_t hdmi_render_cycles_last,hdmi_render_cycles_max;
volatile uint64_t hdmi_render_cycles_total,hdmi_slow_present_intervals;
/* Five-bit rows: digits 0-9, then A-Z. */
static const uint8_t letters[36][7]={
 {14,17,19,21,25,17,14},{4,12,4,4,4,4,14},{14,17,1,2,4,8,31},
 {30,1,1,14,1,1,30},{2,6,10,18,31,2,2},{31,16,16,30,1,1,30},
 {14,16,16,30,17,17,14},{31,1,2,4,8,8,8},{14,17,17,14,17,17,14},
 {14,17,17,15,1,1,14},{14,17,17,31,17,17,17},{30,17,17,30,17,17,30},
 {14,17,16,16,16,17,14},{30,17,17,17,17,17,30},{31,16,16,30,16,16,31},
 {31,16,16,30,16,16,16},{14,17,16,23,17,17,15},{17,17,17,31,17,17,17},
 {14,4,4,4,4,4,14},{7,2,2,2,18,18,12},{17,18,20,24,20,18,17},
 {16,16,16,16,16,16,31},{17,27,21,21,17,17,17},{17,25,21,19,17,17,17},
 {14,17,17,17,17,17,14},{30,17,17,30,16,16,16},{14,17,17,17,21,18,13},
 {30,17,17,30,20,18,17},{15,16,16,14,1,1,30},{31,4,4,4,4,4,4},
 {17,17,17,17,17,17,14},{17,17,17,17,17,10,4},{17,17,17,21,21,21,10},
 {17,17,10,4,10,17,17},{17,17,10,4,4,4,4},{31,1,2,4,8,16,31}};
static unsigned root(unsigned n){
 unsigned r=0,bit=1u<<30;
 while(bit>n)bit>>=2;
 while(bit){if(n>=r+bit){n-=r+bit;r=(r>>1)+bit;}else r>>=1;bit>>=2;}
 return r;
}
static uint16_t color(unsigned r,unsigned g,unsigned b){
 return (uint16_t)((r>>3)|((g>>2)<<5)|((b>>3)<<11));
}
static unsigned wave(unsigned x){x&=255;return x<128?x*2:(255-x)*2;}
static void prepare(void){
 for(unsigned y=0;y<TH;y++)for(unsigned x=0;x<TW;x++){
  int dx=(int)x*2-(TW-1),dy=(int)y*2-(TH-1);
  unsigned ax=dx<0?(unsigned)-dx:(unsigned)dx,ay=dy<0?(unsigned)-dy:(unsigned)dy;
  unsigned radius=root(ax*ax+ay*ay),a;
  /* Continuous octant approximation: division only during initialization. */
  if(ax>=ay)a=ax?32*ay/ax:0;else a=64-32*ax/ay;
  if(dx<0)a=128-a;
  if(dy<0)a=256-a;
  unsigned i=y*TW+x;
  angles[i]=(uint8_t)a;
  depths[i]=(uint16_t)(radius?16384/radius:0);
  lights[i]=(uint8_t)(radius<8?0:radius<24?1:radius<44?2:radius<68?3:radius<96?4:radius<128?5:radius<160?6:7);
 }
 for(unsigned l=0;l<8;l++)for(unsigned h=0;h<256;h++){
  unsigned r=wave(h+80),g=wave(h+166),b=wave(h);
  unsigned gain=l*32+31;
  uint16_t c=color(r*gain/255,g*gain/255,b*gain/255);
  palette[l][h]=(uint64_t)c*UINT64_C(0x0001000100010001);
 }
}
static void label(uintptr_t base,unsigned x,unsigned y,const char *s,uint16_t c){
 volatile uint16_t *p=(volatile uint16_t*)base;
 while(*s){unsigned char ch=(unsigned char)*s++;int g=ch>='0'&&ch<='9'?ch-'0':ch>='A'&&ch<='Z'?ch-'A'+10:-1;
  if(g>=0)for(unsigned r=0;r<7;r++)for(unsigned col=0;col<5;col++)if(letters[g][r]&(1u<<(4-col)))
   for(unsigned dy=0;dy<2;dy++)for(unsigned dx=0;dx<2;dx++)p[(y+r*2+dy)*640+x+col*2+dx]=c;
  x+=12;
 }
}
/* Public to the native painter regression, static in the firmware image. */
static void render(uintptr_t base,unsigned frame){
 unsigned spin=(frame>>1)&255,travel=frame*3;
 for(unsigned y=0;y<TH;y++)for(unsigned x=0;x<TW;x++){
  unsigned i=y*TW+x,u=(angles[i]+spin)&255,v=depths[i]+travel;
  unsigned du=u&31,dv=v&63;
  unsigned edge=du<2||dv<3;
  unsigned glow=du<5||dv<7;
  unsigned l=lights[i];
  unsigned shade=edge?7:glow?5:((u>>5)^(v>>6))&1?2:1;
  if(l<shade)shade=l;
  uint64_t packed=l?palette[shade][(u+v/2+frame)&255]:0;
  volatile uint64_t *p=(volatile uint64_t*)(base+2*((y*4)*640+x*4));
  p[0]=packed;p[160]=packed;p[320]=packed;p[480]=packed;
 }
 label(base,16,14,"NEON TUNNEL",color(190,240,255));
 label(base,16,450,"ROCKET 8 DMA SLOTS",color(190,240,255));
 char digits[9];unsigned n=frame;
 for(int i=7;i>=0;i--){digits[i]=(char)('0'+n%10);n/=10;}digits[8]=0;
 label(base,528,14,digits,color(255,220,180));
}
int main(void){
 (void)fill;stop();REG32(0x18)=8;fence();prepare();
 render(FRAME0,0);render(FRAME1,0);fence();BASE=FRAME0;fence();
 if(!(REG32(0x0c)&2))fail(4);
 REG32(0x08)=1;fence();
 unsigned next=1;uint64_t previous=0;
 for(unsigned frame=1;;frame++){
  uintptr_t base=next?FRAME1:FRAME0;uint64_t start=cycles();
  render(base,frame);fence();uint64_t render_cycles=cycles()-start;
  flip_to(base);uint64_t now=cycles();
  hdmi_stats_seq++;fence();
  if(!previous)hdmi_first_flip_cycle=now;
  else{hdmi_interval_cycles_last=now-previous;
   if(hdmi_interval_cycles_last>hdmi_interval_cycles_max)hdmi_interval_cycles_max=hdmi_interval_cycles_last;
   if(hdmi_interval_cycles_last>2500000)hdmi_slow_present_intervals++;
  }
  previous=now;hdmi_elapsed_cycles=now-hdmi_first_flip_cycle;
  hdmi_render_cycles_last=render_cycles;
  if(render_cycles>hdmi_render_cycles_max)hdmi_render_cycles_max=render_cycles;
  hdmi_render_cycles_total+=render_cycles;hdmi_frames_switched++;hdmi_phase=frame;
  hdmi_status=((uint64_t)REG32(0x0c)<<32)|REG32(0x40);
  fence();hdmi_stats_seq++;fence();
  if(REG32(0x0c)&4)fail(5);
  next^=1;
 }
}
