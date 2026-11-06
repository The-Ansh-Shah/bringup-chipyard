# Historical profiling reference

`pipelined-regarray/VideoController.scala` is the exact local pipelined-DMA source captured before the page-flip work. It is a benchmark fixture, not compiled by the normal generator build. It has eight register-array response slots, runtime limits 1/2/4/8, and the older disable-resets-video behavior. It is not the current hardware and was never a published bitstream.

The original single-read baseline is available at Git commit c11c05a3. To regenerate the historical pipelined baseline, use an independent checkout of that integration/base environment and replace only generators/barduino-blocks/src/main/scala/VideoController/VideoController.scala with this fixture, generate Verilog, and point the profiling/area tools at that collateral. Do not overwrite the active worktree or edit shared submodules. No board pin, shell or software/baremetal-ide changes are needed for this controller comparison.

The fixture SHA-256 and generated dependency hashes can be compared with the saved pipeline profiling metadata. The extra field introduced by later page-flip/BRAM work is intentionally absent here. Current source and measured results are described in ../../pageflip/README.md.
