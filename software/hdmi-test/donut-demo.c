/* Original integer torus renderer: no floating point, libm, malloc or assets.
 * DMA supports eight slots; this program selects all eight while stopped. */
#define HDMI_PAGEFLIP 1
#define HDMI_NO_MAIN 1
#include "main.c"
#define CW 80
#define CH 48
#define Q 16384
volatile uint64_t hdmi_stats_seq,hdmi_first_flip_cycle,hdmi_elapsed_cycles;
volatile uint64_t hdmi_interval_cycles_last,hdmi_interval_cycles_max;
volatile uint64_t hdmi_render_cycles_last,hdmi_render_cycles_max;
volatile uint64_t hdmi_render_cycles_total,hdmi_slow_present_intervals;
volatile uint64_t hdmi_donut_samples,hdmi_donut_visible_cells;
static const int16_t sintab[256]={0,402,804,1205,1606,2006,2404,2801,3196,3590,3981,4370,4756,5139,5520,5897,6270,6639,7005,7366,7723,8076,8423,8765,9102,9434,9760,10080,10394,10702,11003,11297,11585,11866,12140,12406,12665,12916,13160,13395,13623,13842,14053,14256,14449,14635,14811,14978,15137,15286,15426,15557,15679,15791,15893,15986,16069,16143,16207,16261,16305,16340,16364,16379,16384,16379,16364,16340,16305,16261,16207,16143,16069,15986,15893,15791,15679,15557,15426,15286,15137,14978,14811,14635,14449,14256,14053,13842,13623,13395,13160,12916,12665,12406,12140,11866,11585,11297,11003,10702,10394,10080,9760,9434,9102,8765,8423,8076,7723,7366,7005,6639,6270,5897,5520,5139,4756,4370,3981,3590,3196,2801,2404,2006,1606,1205,804,402,0,-402,-804,-1205,-1606,-2006,-2404,-2801,-3196,-3590,-3981,-4370,-4756,-5139,-5520,-5897,-6270,-6639,-7005,-7366,-7723,-8076,-8423,-8765,-9102,-9434,-9760,-10080,-10394,-10702,-11003,-11297,-11585,-11866,-12140,-12406,-12665,-12916,-13160,-13395,-13623,-13842,-14053,-14256,-14449,-14635,-14811,-14978,-15137,-15286,-15426,-15557,-15679,-15791,-15893,-15986,-16069,-16143,-16207,-16261,-16305,-16340,-16364,-16379,-16384,-16379,-16364,-16340,-16305,-16261,-16207,-16143,-16069,-15986,-15893,-15791,-15679,-15557,-15426,-15286,-15137,-14978,-14811,-14635,-14449,-14256,-14053,-13842,-13623,-13395,-13160,-12916,-12665,-12406,-12140,-11866,-11585,-11297,-11003,-10702,-10394,-10080,-9760,-9434,-9102,-8765,-8423,-8076,-7723,-7366,-7005,-6639,-6270,-5897,-5520,-5139,-4756,-4370,-3981,-3590,-3196,-2801,-2404,-2006,-1606,-1205,-804,-402};
static const uint8_t font[36][7]={
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
/* Increasing coverage/brightness: . : - = + * o O # @ */
static const uint8_t glyph[10][8]={
 {0,0,0,0,0,24,24,0},{0,24,24,0,24,24,0,0},
 {0,0,0,126,0,0,0,0},{0,0,126,0,126,0,0,0},
 {0,24,24,126,24,24,0,0},{0,102,60,126,60,102,0,0},
 {0,0,60,102,102,60,0,0},{60,102,195,195,195,102,60,0},
 {108,108,254,108,254,108,108,0},{60,102,219,219,222,96,60,0}
};
static int32_t depth[CW*CH];
static uint8_t shades[CW*CH];
static const uint64_t ink_mask[16]={UINT64_C(0x0000000000000000),UINT64_C(0xffff000000000000),UINT64_C(0x0000ffff00000000),UINT64_C(0xffffffff00000000),UINT64_C(0x00000000ffff0000),UINT64_C(0xffff0000ffff0000),UINT64_C(0x0000ffffffff0000),UINT64_C(0xffffffffffff0000),UINT64_C(0x000000000000ffff),UINT64_C(0xffff00000000ffff),UINT64_C(0x0000ffff0000ffff),UINT64_C(0xffffffff0000ffff),UINT64_C(0x00000000ffffffff),UINT64_C(0xffff0000ffffffff),UINT64_C(0x0000ffffffffffff),UINT64_C(0xffffffffffffffff)};
static inline int32_t mul(int32_t a,int32_t b){return (a*b)/Q;}
static uint16_t color(unsigned r,unsigned g,unsigned b){
 return (uint16_t)(((b>>3)<<11)|((g>>2)<<5)|(r>>3));
}
static void label(uintptr_t base,unsigned x,unsigned y,const char *s,uint16_t c){
 volatile uint16_t *p=(volatile uint16_t*)base;
 while(*s){unsigned ch=(unsigned char)*s++;int g=ch>='0'&&ch<='9'?(int)(ch-'0'):ch>='A'&&ch<='Z'?(int)(ch-'A')+10:-1;
  if(g>=0)for(unsigned row=0;row<7;row++)for(unsigned col=0;col<5;col++)
   if(font[g][row]&(1u<<(4-col)))for(unsigned dy=0;dy<2;dy++)for(unsigned dx=0;dx<2;dx++)
    p[(y+row*2+dy)*640+x+col*2+dx]=c;
  x+=12;
 }
}
static void init_image(uintptr_t base){
 volatile uint64_t *p=(volatile uint64_t*)base;
 uint64_t c=(uint64_t)color(3,7,18)*UINT64_C(0x0001000100010001);
 for(unsigned i=0;i<WORDS;i++)p[i]=c;
 label(base,20,14,"ROCKET 3D ASCII DONUT",color(60,230,255));
 label(base,20,38,"DMA LIMIT 8 READS",color(140,160,220));
 label(base,20,460,"TARGET 60 HZ  MEASURE ACTUAL FPS",color(180,120,255));
}
/* Rotations and perspective use Q14. Bounds: coordinates <=3Q; all products
 * fit signed 32 bits, including projection numerator 58*3Q. */
static void project(unsigned phase){
 for(unsigned i=0;i<CW*CH;i++){depth[i]=-2147483647;shades[i]=0;}
 int32_t sa=sintab[phase&255],ca=sintab[(phase+64)&255];
 int32_t sb=sintab[(phase*3/2+32)&255],cb=sintab[(phase*3/2+96)&255];
 unsigned samples=0;
 for(unsigned u=0;u<256;u+=2){
  int32_t cu=sintab[(u+64)&255],su=sintab[u];
  for(unsigned v=0;v<256;v+=8){
   int32_t cv=sintab[(v+64)&255],sv=sintab[v];
   int32_t radius=2*Q+cv;
   int32_t x=mul(radius,cu),y=mul(radius,su),z=sv;
   int32_t nx=mul(cv,cu),ny=mul(cv,su),nz=sv;
   int32_t ry=mul(y,ca)-mul(z,sa),rz=mul(y,sa)+mul(z,ca);
   int32_t rx=mul(x,cb)+mul(rz,sb);rz=mul(rz,cb)-mul(x,sb);
   int32_t nry=mul(ny,ca)-mul(nz,sa),nrz=mul(ny,sa)+mul(nz,ca);
   int32_t nrx=mul(nx,cb)+mul(nrz,sb);nrz=mul(nrz,cb)-mul(nx,sb);
   int32_t denominator=7*Q-rz;
   int sx=CW/2+rx*58/denominator,sy=CH/2-ry*58/denominator;
   if(sx<0||sx>=CW||sy<0||sy>=CH)continue;
   unsigned idx=(unsigned)sy*CW+(unsigned)sx;
   if(rz<=depth[idx])continue;
   depth[idx]=rz;
   /* Fixed directional light: camera/right/top. Ambient keeps silhouette. */
   int32_t light=nrz+nry/2-nrx/3;
   int level=2+(light*7)/Q;
   if(level<1)level=1;
   if(level>10)level=10;
   shades[idx]=(uint8_t)level;samples++;
  }
 }
 hdmi_donut_samples=samples;
}
static void render(uintptr_t base,unsigned phase){
 project(phase);
 uint16_t palette[11];uint64_t packed_palette[11];palette[0]=color(2,5,13);
 for(unsigned n=1;n<=10;n++){
  unsigned wave=(unsigned)(sintab[(phase+n*4)&255]+Q)>>9;
  palette[n]=color(12+n*14+wave,35+n*19,65+n*18);
 }
 for(unsigned n=0;n<=10;n++)packed_palette[n]=(uint64_t)palette[n]*UINT64_C(0x0001000100010001);
 unsigned visible=0;
 for(unsigned cy=0;cy<CH;cy++)for(unsigned cx=0;cx<CW;cx++){
  unsigned level=shades[cy*CW+cx];
  if(level)visible++;
  uint64_t bg=packed_palette[0],difference=bg^packed_palette[level];
  for(unsigned row=0;row<8;row++){
   unsigned bits=level?glyph[level-1][row]:0;
   volatile uint64_t *dst=(volatile uint64_t*)(base+2*((64+cy*8+row)*640+cx*8));
   dst[0]=bg^(difference&ink_mask[bits>>4]);
   dst[1]=bg^(difference&ink_mask[bits&15]);
  }
 }
 hdmi_donut_visible_cells=visible;
}
int main(void){
 (void)fill;stop();REG32(0x18)=8;fence();
 init_image(FRAME0);init_image(FRAME1);render(FRAME0,0);
 BASE=FRAME0;fence();if(!(REG32(0x0c)&2))fail(4);
 REG32(0x08)=1;fence();
 unsigned next=1;uint64_t previous=0;
 for(uint64_t frame=1;;frame++){
  uintptr_t base=next?FRAME1:FRAME0;
  uint64_t begin=cycles();render(base,(unsigned)frame);fence();
  uint64_t spent=cycles()-begin;flip_to(base);uint64_t now=cycles();
  hdmi_stats_seq++;fence();
  if(!previous)hdmi_first_flip_cycle=now;
  else{hdmi_interval_cycles_last=now-previous;
   if(hdmi_interval_cycles_last>hdmi_interval_cycles_max)hdmi_interval_cycles_max=hdmi_interval_cycles_last;
   if(hdmi_interval_cycles_last>2500000)hdmi_slow_present_intervals++;
  }
  previous=now;hdmi_elapsed_cycles=now-hdmi_first_flip_cycle;
  hdmi_render_cycles_last=spent;if(spent>hdmi_render_cycles_max)hdmi_render_cycles_max=spent;
  hdmi_render_cycles_total+=spent;hdmi_frames_switched++;hdmi_phase=frame&255;
  hdmi_status=((uint64_t)REG32(0x0c)<<32)|REG32(0x40);
  fence();hdmi_stats_seq++;fence();
  if(REG32(0x0c)&4)fail(5);
  next^=1;
 }
}
