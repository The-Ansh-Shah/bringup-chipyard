#!/usr/bin/env python3
"""Native test of actual C painters: dirty updates versus independent full redraws."""
from pathlib import Path
import subprocess,tempfile
root=Path(__file__).resolve().parents[3]
source=(root/'software/hdmi-test/animated-demo.c').read_text()
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
static unsigned char front_copy[614400];
static uintptr_t front=FRAME0;
static int bounce(int start,int velocity,unsigned frame,int low,int high){
 int width=high-low,phase=(start-low+velocity*(int)frame)%(2*width);
 return low+(phase<=width?phase:2*width-phase);
}
static void reference(unsigned frame){
 uintptr_t ref=(uintptr_t)0x81200000;
 initialize(ref);
 smiley(ref,(unsigned)bounce(64,4,frame,16,560),
        (unsigned)bounce(160,2,frame,128,400),frame%120>=112);
 counter(ref,frame);
}
static void flip_to(uintptr_t addr){
 unsigned frame=++checked;
 if(frame==1){
  reference(0);
  if(memcmp((void*)FRAME0,(void*)0x81200000,614400))abort();
  memcpy(front_copy,(void*)front,614400);
 }
 if(memcmp((void*)front,front_copy,614400)){
  fprintf(stderr,"active buffer modified at frame %u\n",frame);abort();
 }
 if(addr!=(frame%2?FRAME1:FRAME0))abort();
 reference(frame);
 if(memcmp((void*)addr,(void*)0x81200000,614400)){
  uint16_t *actual=(uint16_t*)addr,*expect=(uint16_t*)0x81200000;
  for(unsigned i=0;i<307200;i++)if(actual[i]!=expect[i]){
   fprintf(stderr,"frame=%u x=%u y=%u actual=%04x expected=%04x\n",frame,i%640,i/640,actual[i],expect[i]);break;
  }
  abort();
 }
 front=addr;memcpy(front_copy,(void*)front,614400);
 if(checked==500)longjmp(finished,1);
}
int main(void){
 void *p=mmap((void*)FRAME0,0x300000,PROT_READ|PROT_WRITE,
              MAP_PRIVATE|MAP_ANONYMOUS|MAP_FIXED_NOREPLACE,-1,0);
 if(p==MAP_FAILED){perror("mmap");return 1;}
 regs[3]=2;
 if(!setjmp(finished))firmware_main();
 printf("PASS: %u dirty updates match independent full redraws; active buffers unchanged; bounces/blinks/digits covered\n",checked);
 munmap((void*)FRAME0,0x300000);return 0;
}
'''
with tempfile.TemporaryDirectory(prefix='hdmi-animation-test-') as d:
 src=Path(d)/'render-test.c';exe=Path(d)/'render-test'
 src.write_text(source.replace('#include "main.c"',shim)+checks)
 subprocess.run(['cc','-O2','-Wall','-Wextra','-Werror',str(src),'-o',str(exe)],check=True)
 subprocess.run([str(exe)],check=True)
