# Scanout RAM reset warning disposition

The expanded post-route DRC reports 21 REQP-1839 warnings, all on chiptop0/system/scanout/data/data_ext/mem_0_0/ram_reg. Address/read-enable fan-in includes asynchronously reset queue/reset-control registers. Reset transitions can violate RAM control timing; ordinary STA does not establish safe behavior during assertion. No warning severity was changed or suppressed. scripts/audit-boralake-drc.tcl expands the message limit and asserts zero DRC errors.

Normal page flips do not reset the RAM or HDMI timing. Hardware reset invalidates all allocated DMA slots; software disable drains/discards requests, flushes stream state and the retirement queue. Before any newly allocated slot is read, all eight fresh response beats must arrive and its completion state must be set. Old RAM contents are therefore invalid after reset and must never be displayed. Regression tests exercise reset/restart, disable/drain, cancellation and response errors.

This protocol is the disposition for retaining the candidate with the warning. RTL simulation does not model analog reset-edge corruption and is not a formal proof of reset safety. Physical reset/restart and display testing remain required. The full CDC report retains 17 critical CDC-10 findings; this candidate is not represented as clean CDC or production sign-off.
