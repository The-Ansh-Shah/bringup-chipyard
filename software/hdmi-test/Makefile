CROSS ?= /bwrcq/C/ansh_shah/ee194-lab1/.conda-env/riscv-tools/bin/riscv64-unknown-elf-
CC := $(CROSS)gcc
CFLAGS := -O2 -g -Wall -Wextra -Werror -march=rv64imac_zicsr_zifencei -mabi=lp64 -mcmodel=medany -msmall-data-limit=0 -ffreestanding -fno-builtin -fno-pie
all: build/hdmi-test.elf
build/hdmi-test.elf: main.c start.S link.ld
	mkdir -p build
	$(CC) $(CFLAGS) -nostdlib -nostartfiles -static -Wl,--no-relax,-T,link.ld,-Map,build/hdmi-test.map -o $@ start.S main.c
	$(CROSS)objdump -d $@ > build/hdmi-test.dis
	$(CROSS)readelf -lW $@ > build/hdmi-test.segments
clean:
	rm -rf build
.PHONY: all clean

# Hold one image indefinitely; existing build/hdmi-test.elf is left untouched.
HOLD_ELFS := build/hdmi-hold-bars.elf build/hdmi-hold-red.elf build/hdmi-hold-green.elf build/hdmi-hold-blue.elf
hold: $(HOLD_ELFS)
build/hdmi-hold-bars.elf: HOLD_PHASE=0
build/hdmi-hold-red.elf: HOLD_PHASE=1
build/hdmi-hold-green.elf: HOLD_PHASE=2
build/hdmi-hold-blue.elf: HOLD_PHASE=3
$(HOLD_ELFS): build/hdmi-hold-%.elf: main.c start.S link.ld
	mkdir -p build
	$(CC) $(CFLAGS) -DHDMI_HOLD_PHASE=$(HOLD_PHASE) -nostdlib -nostartfiles -static -Wl,--no-relax,-T,link.ld,-Map,$(@:.elf=.map) -o $@ start.S main.c
	$(CROSS)objdump -d $@ > $(@:.elf=.dis)
	$(CROSS)readelf -lW $@ > $(@:.elf=.segments)
.PHONY: hold

pageflip: build/hdmi-pageflip.elf build/hdmi-flip-stress.elf
build/hdmi-pageflip.elf: main.c start.S link.ld
	mkdir -p build
	$(CC) $(CFLAGS) -DHDMI_PAGEFLIP=1 -nostdlib -nostartfiles -static -Wl,--no-relax,-T,link.ld,-Map,$(@:.elf=.map) -o $@ start.S main.c
	$(CROSS)objdump -d $@ > $(@:.elf=.dis)
	$(CROSS)readelf -lW $@ > $(@:.elf=.segments)
build/hdmi-flip-stress.elf: flip-stress.c main.c start.S link.ld
	mkdir -p build
	$(CC) $(CFLAGS) -nostdlib -nostartfiles -static -Wl,--no-relax,-T,link.ld,-Map,$(@:.elf=.map) -o $@ start.S flip-stress.c
	$(CROSS)objdump -d $@ > $(@:.elf=.dis)
	$(CROSS)readelf -lW $@ > $(@:.elf=.segments)
.PHONY: pageflip

animated: build/hdmi-animated-demo.elf
build/hdmi-animated-demo.elf: animated-demo.c main.c start.S link.ld Makefile
	mkdir -p build
	$(CC) $(CFLAGS) -nostdlib -nostartfiles -static -Wl,--no-relax,-T,link.ld,-Map,$(@:.elf=.map) -o $@ start.S animated-demo.c
	$(CROSS)objdump -d $@ > $(@:.elf=.dis)
	$(CROSS)readelf -lW $@ > $(@:.elf=.segments)
.PHONY: animated

# Procedural render stress demos: matched continuous HDMI hardware, 8 DMA slots.
DEMOS := donut plasma tunnel
demos: $(addprefix build/hdmi-,$(addsuffix .elf,$(DEMOS))) build/hdmi-flip-stress-8.elf animated pageflip
$(addprefix build/hdmi-,$(addsuffix .elf,$(DEMOS))): build/hdmi-%.elf: %-demo.c main.c start.S link.ld Makefile
	mkdir -p build
	$(CC) $(CFLAGS) -nostdlib -nostartfiles -static -Wl,--no-relax,-T,link.ld,-Map,$(@:.elf=.map) -o $@ start.S $<
	$(CROSS)objdump -d $@ > $(@:.elf=.dis)
	$(CROSS)readelf -lW $@ > $(@:.elf=.segments)
build/hdmi-flip-stress-8.elf: flip-stress.c main.c start.S link.ld Makefile
	mkdir -p build
	$(CC) $(CFLAGS) -DHDMI_DMA_LIMIT=8 -nostdlib -nostartfiles -static -Wl,--no-relax,-T,link.ld,-Map,$(@:.elf=.map) -o $@ start.S flip-stress.c
	$(CROSS)objdump -d $@ > $(@:.elf=.dis)
	$(CROSS)readelf -lW $@ > $(@:.elf=.segments)
.PHONY: demos
