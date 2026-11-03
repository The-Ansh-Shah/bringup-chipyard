# Read-only sign-off of an implemented checkpoint. Optional argv[0] overrides DCP.
set root [file normalize [file join [file dirname [info script]] ..]]
set wrkdir [file join $root fpga/generated-src/chipyard.fpga.boralakeold.BoraLakeHarness.BoraLakeRocketConfig/obj]
set checkpoint [file join $wrkdir post_route.dcp]
if {$argc == 1} { set checkpoint [lindex $argv 0] }
if {$argc > 1} { error {Usage: -tclargs [optional-checkpoint]} }
open_checkpoint $checkpoint
file mkdir [file join $wrkdir report]
source [file join $root fpga/hdmi-shell/tcl/cdc-check.tcl]
exit
