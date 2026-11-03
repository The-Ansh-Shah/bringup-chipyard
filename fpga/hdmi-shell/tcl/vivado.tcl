# Same checkout board/implementation stages; omit only legacy uninstantiated IP.
set local_scriptdir [file dirname [file normalize [info script]]]
set scriptdir [file normalize [file join $local_scriptdir ../../fpga-shells/xilinx/common/tcl]]
source [file join $scriptdir prologue.tcl]
# prologue has already sourced the original board.tcl and fixed constraintsdir.
# This directory has no optional board ip.tcl: generated PLL/MIG Tcl is sufficient.
set boarddir $local_scriptdir
source [file join $scriptdir init.tcl]
# create_ip already registered each XCI; do not read_ip a second time or glob stale IP.
synth_design -top $top -flatten_hierarchy rebuilt
write_checkpoint -force [file join $wrkdir post_synth]
if {[info exists pre_impl_debug_tcl]} { source [file join $scriptdir $pre_impl_debug_tcl] }
# Preserve discrete library synchronizer FF chains and guide their placement.
set syncRegs [get_cells -hier -filter {NAME =~ *output_chain*sync_*_reg* && IS_SEQUENTIAL == 1}]
if {[llength $syncRegs]} { set_property ASYNC_REG TRUE $syncRegs }
source [file join $scriptdir opt.tcl]
if {[info exists ::env(HDMI_INCREMENTAL_DCP)]} {
 read_checkpoint -incremental [file normalize $::env(HDMI_INCREMENTAL_DCP)]
}
if {[info exists post_opt_debug_tcl]} { source [file join $scriptdir $post_opt_debug_tcl] }
source [file join $scriptdir place.tcl]
source [file join $scriptdir route.tcl]
source [file join $scriptdir bitstream.tcl]
if {[info exists post_impl_debug_tcl]} { source [file join $scriptdir $post_impl_debug_tcl] }
# Additional sign-off reports do not waive the original timing/DRC checks.
file mkdir [file join $wrkdir report]
source [file join $local_scriptdir cdc-check.tcl]
report_cdc -details -file [file join $wrkdir report cdc.txt]
check_timing -verbose -file [file join $wrkdir report check_timing.txt]

source [file join $scriptdir report.tcl]
set holdPaths [get_timing_paths -delay_type min -max_paths 1]
if {[llength $holdPaths] && [get_property SLACK [lindex $holdPaths 0]] < 0} { error "Hold timing failed" }
set drcErrors [get_drc_violations -quiet -filter {SEVERITY == Error}]
if {[llength $drcErrors]} { error "Unresolved DRC errors: $drcErrors" }
