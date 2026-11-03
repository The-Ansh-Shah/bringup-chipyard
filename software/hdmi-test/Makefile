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
