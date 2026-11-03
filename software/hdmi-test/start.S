.section .text.init,"ax",@progbits
.global _start
_start:
  csrw mie, zero
  csrw satp, zero
  la sp, __stack_top
  la t0, trap
  csrw mtvec, t0
  la t0, __bss_start
  la t1, __bss_end
1: bgeu t0, t1, 2f
  sd zero, 0(t0)
  addi t0, t0, 8
  j 1b
2: fence.i
  call main
3: wfi
  j 3b
.balign 4
trap:
  csrr t0, mcause
  la t1, hdmi_trap_cause
  sd t0, 0(t1)
  csrr t0, mepc
  la t1, hdmi_trap_pc
  sd t0, 0(t1)
  li t0, 3
  la t1, tohost
  sd t0, 0(t1)
4: j 4b
