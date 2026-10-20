# Keep Vivado's internal parallelism bounded on the shared build machine.
set_param general.maxThreads 8
source [file normalize [file join [file dirname [info script]] .. fpga-shells xilinx common tcl vivado.tcl]]
