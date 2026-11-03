# Run explicitly only when the operator intends to program hardware.
if {$argc != 4} { error "Usage: -tclargs SERVER CABLE_TARGET DEVICE BITSTREAM" }
lassign $argv server targetName deviceName bit
if {![file isfile $bit]} { error "Bitstream does not exist: $bit" }
open_hw_manager
connect_hw_server -url $server
set targets [get_hw_targets $targetName]
if {[llength $targets] != 1} { error "Select exactly one cable target: $targets" }
current_hw_target [lindex $targets 0]
open_hw_target
set devices [get_hw_devices $deviceName]
if {[llength $devices] != 1} { error "Select exactly one FPGA device: $devices" }
set dev [lindex $devices 0]
if {![string match xc7k160t* [get_property PART $dev]]} { error "Unexpected FPGA part" }
current_hw_device $dev
refresh_hw_device $dev
set_property PROGRAM.FILE [file normalize $bit] $dev
program_hw_devices $dev
close_hw_manager
