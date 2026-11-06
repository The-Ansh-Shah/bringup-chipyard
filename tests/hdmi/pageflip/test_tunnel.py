#!/usr/bin/env python3
"""Actual C tunnel painter: bounds, deterministic samples, motion, and flip lifecycle."""
from pathlib import Path
import subprocess, tempfile
root=Path(__file__).resolve().parents[3]
source=(root/'software/hdmi-test/tunnel-demo.c').read_text()
shim=r'''
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <setjmp.h>
#include <sys/mman.h>
#define FRAME0 ((uintptr_t)0x81000000)
#define FRAME1 ((uintptr_t)0x81100000)
static uint32_t regs[32];
static uintptr_t fake_base;
#define REG32(off) (regs[(off)/4])
#define BASE fake_base
volatile uint64_t hdmi_phase,hdmi_frames_switched,hdmi_status;
static uint64_t fake_cycles;
static inline uint64_t cycles(void){return ++fake_cycles;}
static inline void fence(void){}
static void fail(unsigned n){fprintf(stderr,"firmware fail %u\n",n);abort();}
static void stop(void){}
static void fill(uintptr_t a,unsigned p){(void)a;(void)p;}
static void flip_to(uintptr_t addr);
#define main firmware_main
'''
checks=r'''
#undef main
static jmp_buf finished;
static unsigned checked;
static unsigned char front_copy[614400],expected[614400] __attribute__((aligned(8)));
static uintptr_t front=FRAME0;
static uint16_t reference(unsigned x,unsigned y,unsigned frame){
 unsigned i=(y/4)*TW+x/4,u=(angles[i]+(frame>>1))&255,v=depths[i]+frame*3;
 unsigned du=u%32,dv=v%64;
 unsigned shade=du<2||dv<3?7:du<5||dv<7?5:((u/32)^(v/64))&1?2:1;
 unsigned l=lights[i];if(l<shade)shade=l;
 if(!l)return 0;
 return (uint16_t)palette[shade][(u+v/2+frame)&255];
}
static void flip_to(uintptr_t addr){
 unsigned frame=++checked;
 if(frame==1)memcpy(front_copy,(void*)front,614400);
 if(regs[0x18/4]!=8||fake_base!=FRAME0||addr!=(frame%2?FRAME1:FRAME0))abort();
 if(memcmp((void*)front,front_copy,614400)){fprintf(stderr,"active framebuffer changed\n");abort();}
 render((uintptr_t)expected,frame);
 if(memcmp(expected,(void*)addr,614400)){fprintf(stderr,"nondeterministic render\n");abort();}
 uint16_t *p=(uint16_t*)addr;unsigned changed=0;
 for(unsigned y=40;y<440;y++)for(unsigned x=0;x<640;x++){
  if(p[y*640+x]!=reference(x,y,frame)){fprintf(stderr,"bad pixel %u %u\n",x,y);abort();}
  if(p[y*640+x]!=((uint16_t*)front_copy)[y*640+x])changed++;
 }
 if(changed<10000){fprintf(stderr,"no substantial animation %u\n",changed);abort();}
 for(uintptr_t a=FRAME0+614400;a<FRAME1;a++)if(*(unsigned char*)a!=0x5a)abort();
 for(uintptr_t a=FRAME1+614400;a<FRAME0+0x200000;a++)if(*(unsigned char*)a!=0x5a)abort();
 front=addr;memcpy(front_copy,(void*)front,614400);
 if(checked==32)longjmp(finished,1);
}
int main(void){
 void *p=mmap((void*)FRAME0,0x200000,PROT_READ|PROT_WRITE,
              MAP_PRIVATE|MAP_ANONYMOUS|MAP_FIXED_NOREPLACE,-1,0);
 if(p==MAP_FAILED){perror("mmap");return 1;}
 memset(p,0x5a,0x200000);regs[3]=2;
 if(!setjmp(finished))firmware_main();
 FILE *f=fopen("/tmp/hdmi-tunnel-preview.ppm","wb");
 if(f){fprintf(f,"P6\n640 480\n255\n");uint16_t *pixels=(uint16_t*)front;
  for(unsigned i=0;i<307200;i++){unsigned c=pixels[i];
   unsigned char rgb[3]={(unsigned char)((c&31)*255/31),(unsigned char)(((c>>5)&63)*255/63),(unsigned char)((c>>11)*255/31)};
   fwrite(rgb,1,3,f);
  }fclose(f);
 }
 printf("PASS: %u frames; exact procedural pixels, changing images, deterministic renders, untouched active buffers/gaps, DMA limit 8\n",checked);
 munmap((void*)FRAME0,0x200000);return 0;
}
'''
with tempfile.TemporaryDirectory(prefix='hdmi-tunnel-test-') as d:
 src=Path(d)/'test.c';exe=Path(d)/'test'
 src.write_text(source.replace('#include "main.c"',shim)+checks)
 subprocess.run(['cc','-O2','-Wall','-Wextra','-Werror','-fsanitize=undefined',str(src),'-o',str(exe)],check=True)
 subprocess.run([str(exe)],check=True)
