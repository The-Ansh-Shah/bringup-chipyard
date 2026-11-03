# Status CDC must remain nine discrete ASYNC_REG flip-flops, not SRLs.
set statusFFs [get_cells -hier -filter {NAME =~ *scanout/*_sync/stages_reg* && IS_SEQUENTIAL == 1}]
if {[llength $statusFFs] != 9} { error "Expected nine status synchronizer FFs: $statusFFs" }
foreach ff $statusFFs {
 if {[get_property ASYNC_REG $ff] ni {1 true TRUE}} { error "Missing ASYNC_REG on $ff" }
}
set statusSRLs [get_cells -hier -filter {NAME =~ *scanout/*_sync/* && REF_NAME =~ SRL*}]
if {[llength $statusSRLs]} { error "Status synchronizer inferred SRL: $statusSRLs" }
puts "HDMI_CDC status: nine discrete ASYNC_REG FFs, zero SRLs"
# Analyze the implemented Gray-pointer crossings independently of async clock groups.
# Fast source toggles at 100 MHz (10 ns); slow source at ~25.179 MHz (~39.72 ns).
foreach {srcPattern dstPattern bound label} {
 *scanout/fifo/source/* *scanout/fifo/sink/*widx* 8.0 write_gray
 *scanout/fifo/sink/* *scanout/fifo/source/*ridx* 30.0 read_gray
} {
 # Synthesis may merge the Gray MSB with the equivalent binary-counter MSB.
 # Identify the actual cross-clock first-stage D pins and their launching Qs.
 set fromPins {}
 set toPins {}
 set candidates [get_pins -of_objects [get_cells -hier -filter "NAME =~ $dstPattern"] -filter {REF_PIN_NAME == D}]
 foreach dst $candidates {
  set starts [all_fanin -flat -startpoints_only -to $dst]
  set sourceCells [filter [get_cells -quiet -of_objects $starts] "NAME =~ $srcPattern"]
  set sources [get_pins -quiet -of_objects $sourceCells -filter {REF_PIN_NAME == Q}]
  if {[llength $sources]} {
   lappend toPins $dst
   foreach src $sources { lappend fromPins $src }
  }
 }
 set fromPins [lsort -unique $fromPins]
 set toPins [lsort -unique $toPins]
 puts "HDMI_CDC $label sources=[llength $fromPins] destinations=[llength $toPins] bound=$bound ns"
 if {[llength $fromPins] != 7 || [llength $toPins] != 7} {
  error "Cannot identify complete seven-bit scanout Gray crossing $label: $fromPins -> $toPins"
 }
 set_bus_skew $bound -from [get_cells -of_objects $fromPins] -to $toPins
}
set skewReport [report_bus_skew -return_string]
set fp [open [file join $wrkdir report scanout_bus_skew.txt] w]
puts $fp $skewReport
close $fp
if {![string match *Slack* $skewReport]} { error "No analyzable Gray bus-skew paths" }
if {[string match *VIOLATED* $skewReport]} { error "Scanout Gray bus skew failed" }
