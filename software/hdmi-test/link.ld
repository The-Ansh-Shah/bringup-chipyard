OUTPUT_ARCH(riscv)
ENTRY(_start)
MEMORY { RAM (rwx) : ORIGIN = 0x80000000, LENGTH = 2M }
PHDRS { load PT_LOAD; noload PT_NULL; }
SECTIONS {
 . = ORIGIN(RAM);
 .text : { KEEP(*(.text.init)) *(.text .text.*) } > RAM :load
 .rodata : ALIGN(16) { *(.rodata .rodata.*) } > RAM :load
 .data : ALIGN(16) { *(.data .data.*) *(.sdata .sdata.*) } > RAM :load
 .htif : ALIGN(64) { *(.htif) } > RAM :load
 .bss (NOLOAD) : ALIGN(8) { __bss_start = .; *(.bss .bss.* .sbss .sbss.*) *(COMMON) . = ALIGN(8); __bss_end = .; } > RAM :noload
 .stack (NOLOAD) : ALIGN(16) { . += 64K; __stack_top = .; } > RAM :noload
 ASSERT(__stack_top < 0x81000000, "Program/stack overlaps framebuffer")
 /DISCARD/ : { *(.eh_frame .comment) }
}
