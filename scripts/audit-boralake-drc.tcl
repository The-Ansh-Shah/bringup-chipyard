# Read-only audit: expand the default per-rule warning limit, never waive severity.
set repo [file normalize [file join [file dirname [info script]] ..]]
set obj [file join $repo fpga generated-src chipyard.fpga.boralakeold.BoraLakeHarness.BoraLakeRocketConfig obj]
set checkpoint [file join $obj post_route.dcp]
set output [file join $obj report drc-expanded.txt]
if {$argc > 0} { set checkpoint [lindex $argv 0] }
if {$argc > 1} { set output [lindex $argv 1] }
open_checkpoint $checkpoint
set_property MAX_MESSAGES 10000 [get_drc_checks REQP-1839]
report_drc -file $output
set errors [get_drc_violations -quiet -filter {SEVERITY == Error}]
if {[llength $errors]} { error "DRC errors: $errors" }
puts "Expanded DRC audit complete: $output; zero Error-severity violations"
exit
