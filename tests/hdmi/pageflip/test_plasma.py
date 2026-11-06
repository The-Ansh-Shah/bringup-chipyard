#!/usr/bin/env python3
"""Native validation of the actual plasma painter, no hardware MMIO."""
from pathlib import Path
import subprocess,tempfile
root=Path(__file__).resolve().parents[3]
source=(root/'software/hdmi-test/plasma-demo.c').read_text()
shim=r'''
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
static uint32_t regs[32];
#define REG32(off) (regs[(off)/4])
#define FRAME0 ((uintptr_t)0)
#define FRAME1 ((uintptr_t)0)
static uintptr_t base_reg;
#define BASE base_reg
volatile uint64_t hdmi_frames_switched,hdmi_phase,hdmi_status;
static uint64_t cycles(void){return 0;}
static void fence(void){}
static void stop(void){}
static void fill(uintptr_t a,unsigned b){(void)a;(void)b;}
static void fail(unsigned n){(void)n;abort();}
static void flip_to(uintptr_t a){(void)a;}
#define main firmware_main
'''
checks=r'''
#undef main
#define N 307200
static uint64_t hash(const uint16_t *p){
 uint64_t h=1469598103934665603ull;
 for(unsigned i=0;i<N;i++){h^=p[i];h*=1099511628211ull;}
 return h;
}
int main(int argc,char **argv){
 uint16_t *a=malloc(N*2),*b=malloc(N*2);
 if(!a||!b)abort();prepare();
 for(unsigned i=0;i<N;i++)a[i]=(uint16_t)(i*17);
 uint64_t front=hash(a),previous=0;unsigned changed=0;
 for(unsigned f=0;f<300;f++){
  memset(b,0xa5,N*2);render((uintptr_t)b,f);
  for(unsigned y=0;y<120;y++)for(unsigned x=0;x<160;x++){
   int v=wave[(x*3+f)&255]+wave[(y*4+256-(f&255))&255]
       +wave[(radius_map[y][x]+2*f)&255];
   uint16_t expect=palette[(unsigned)((v+384)/3+(f&255))&255];
   for(unsigned dy=0;dy<4;dy++)for(unsigned dx=0;dx<4;dx++)
    if(b[(y*4+dy)*640+x*4+dx]!=expect)abort();
  }
  hud((uintptr_t)b,f);
  uint64_t h=hash(b);if(f&&h!=previous)changed++;previous=h;
  if(hash(a)!=front)abort();
 }
 render((uintptr_t)a,99);hud((uintptr_t)a,99);
 render((uintptr_t)b,99);hud((uintptr_t)b,99);
 if(memcmp(a,b,N*2)||changed!=299)abort();
 if(argc==2){
  FILE *out=fopen(argv[1],"wb");if(!out)abort();
  fprintf(out,"P6\n640 480\n255\n");
  for(unsigned i=0;i<N;i++){
   uint16_t c=a[i];unsigned char rgb[3]={
    (unsigned char)((c&31)*255/31),(unsigned char)(((c>>5)&63)*255/63),
    (unsigned char)((c>>11)*255/31)};
   fwrite(rgb,1,3,out);
  }
  fclose(out);
 }
 free(a);free(b);
 puts("PASS: 300 full frames; reference pixels/block expansion; front unchanged; deterministic output; 299 animated changes; ASan/UBSan");return 0;
}
'''
with tempfile.TemporaryDirectory(prefix='hdmi-plasma-test-') as d:
 src=Path(d)/'test.c';exe=Path(d)/'test'
 src.write_text(source.replace('#include "main.c"',shim)+checks)
 subprocess.run(['cc','-O1','-g','-fsanitize=address,undefined','-fno-omit-frame-pointer',str(src),'-o',str(exe)],check=True)
 import sys
 subprocess.run([str(exe),*sys.argv[1:]],check=True)
