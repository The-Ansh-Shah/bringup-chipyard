#!/usr/bin/env python3
"""Exercise the actual fixed-point C torus painter with ASan/UBSan and guards.
No simulated hardware FPS claim. Optional --preview writes frame-32 PPM.
"""
from pathlib import Path
import argparse, subprocess, tempfile
root=Path(__file__).resolve().parents[3]
a=argparse.ArgumentParser();a.add_argument('--preview',type=Path);args=a.parse_args()
s=(root/'software/hdmi-test/donut-demo.c').read_text()
shim=r'''
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <assert.h>
#define FRAME0 ((uintptr_t)0x81000000)
#define FRAME1 ((uintptr_t)0x81100000)
#define WORDS (640*480/4)
static uint32_t regs[32];static uintptr_t fake_base;
#define REG32(off) regs[(off)/4]
#define BASE fake_base
volatile uint64_t hdmi_phase,hdmi_frames_switched,hdmi_status;
static inline uint64_t cycles(void){return 0;}
static inline void fence(void){}
static void fill(uintptr_t a,unsigned p){(void)a;(void)p;}
static void fail(unsigned n){(void)n;abort();}
static void stop(void){}
static void flip_to(uintptr_t addr){(void)addr;}
#define main firmware_main
'''
s=s.replace('#include "main.c"',shim)
# Independently visit the same surface samples in reverse order. The nearest
# depth must not depend on submission order; ties can differ in quantized light.
start=s.index('static void project(unsigned phase)')
end=s.index('static void render(',start)
reverse=s[start:end].replace('project(unsigned phase)','project_reverse(unsigned phase)').replace('for(unsigned u=0;u<256;u+=2)','for(int u=254;u>=0;u-=2)').replace('for(unsigned v=0;v<256;v+=8)','for(int v=248;v>=0;v-=8)')
s+=reverse+r'''
#undef main
int main(int argc,char **argv){
 uint64_t *storage=malloc(614400+32);assert(storage);
 memset(storage,0x5a,614400+32);
 uintptr_t base=(uintptr_t)(storage+2);
 init_image(base);
 uint64_t first=0;unsigned changed=0,min_cells=3840,max_cells=0;
 int32_t reference_depth[CW*CH];
 for(unsigned frame=0;frame<256;frame++){
  render(base,frame);
  unsigned cells=0;
  for(unsigned i=0;i<CW*CH;i++){
   assert(shades[i]<=10);
   if(shades[i]){cells++;assert(depth[i]>=-3*Q-4&&depth[i]<=3*Q+4);}
  }
  assert(cells>100&&cells<2000);
  if(cells<min_cells)min_cells=cells;
  if(cells>max_cells)max_cells=cells;
  memcpy(reference_depth,depth,sizeof(depth));project_reverse(frame);
  assert(!memcmp(reference_depth,depth,sizeof(depth)));
  assert(storage[0]==UINT64_C(0x5a5a5a5a5a5a5a5a));
  assert(storage[1]==UINT64_C(0x5a5a5a5a5a5a5a5a));
  assert(storage[76802]==UINT64_C(0x5a5a5a5a5a5a5a5a));
  assert(storage[76803]==UINT64_C(0x5a5a5a5a5a5a5a5a));
  uint64_t hash=UINT64_C(1469598103934665603);
  for(unsigned i=0;i<76800;i++)hash=(hash^((uint64_t*)base)[i])*UINT64_C(1099511628211);
  if(frame==0)first=hash;else if(hash!=first)changed++;
  if(frame==32&&argc==2){
   FILE *out=fopen(argv[1],"wb");assert(out);fprintf(out,"P6\n640 480\n255\n");
   uint16_t *pixels=(uint16_t*)base;
   for(unsigned i=0;i<307200;i++){
    unsigned p=pixels[i];unsigned char rgb[3]={(p&31)*255/31,((p>>5)&63)*255/63,((p>>11)&31)*255/31};
    assert(fwrite(rgb,1,3,out)==3);
   }fclose(out);
  }
 }
 assert(changed>250);
 printf("PASS: 256 torus orientations; nearest-depth invariant under reversed samples; animated output; cells %u..%u; framebuffer guards and sanitizers clean\n",min_cells,max_cells);
 free(storage);return 0;
}
'''
with tempfile.TemporaryDirectory(prefix='hdmi-donut-test-') as d:
 c=Path(d)/'test.c';exe=Path(d)/'test';c.write_text(s)
 subprocess.run(['cc','-O2','-Wall','-Wextra','-Werror','-fsanitize=address,undefined','-fno-sanitize-recover=all',str(c),'-o',str(exe)],check=True)
 command=[str(exe)]
 if args.preview:
  args.preview.parent.mkdir(parents=True,exist_ok=True);command.append(str(args.preview.resolve()))
 subprocess.run(command,check=True)
