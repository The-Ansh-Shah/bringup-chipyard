// See LICENSE for license details.
package chipyard.fpga.boralakeold

import org.chipsalliance.cde.config._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.devices.debug._
import freechips.rocketchip.devices.tilelink._
import org.chipsalliance.diplomacy._
import org.chipsalliance.diplomacy.lazymodule._
import freechips.rocketchip.system._
import freechips.rocketchip.tile._

import sifive.blocks.devices.uart._
import sifive.fpgashells.shell.{DesignKey}

import testchipip.serdes._

import chipyard.{BuildSystem}
import org.chipsalliance.cde.config.{Config}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.subsystem.{MBUS, SBUS}
import testchipip.soc.{OBUS}

// don't use FPGAShell's DesignKey
class WithNoDesignKey extends Config((site, here, up) => {
  case DesignKey => (p: Parameters) => new SimpleLazyRawModule()(p)
})

// By default, this uses the on-board USB-UART for the TSI-over-UART link
class WithBoraLakeTweaks(freqMHz: Double = 20) extends Config(
  new WithBoraLakeUARTTSI ++
  new WithBoraLakeTDDRTL ++

  new WithNoDesignKey ++
  new testchipip.tsi.WithUARTTSIClient(initBaudRate = 921600) ++
  new chipyard.harness.WithSerialTLTiedOff ++
  new chipyard.harness.WithHarnessBinderClockFreqMHz(freqMHz) ++
  new chipyard.config.WithUniformBusFrequencies(freqMHz) ++
  new chipyard.harness.WithAllClocksFromHarnessClockInstantiator ++
  new chipyard.clocking.WithPassthroughClockGenerator ++

  new chipyard.config.WithTLBackingMemory ++ // FPGA-shells converts the AXI to TL for us
  new freechips.rocketchip.subsystem.WithExtMemSize(BigInt(1) << 30) ++ // expose lower 1 GiB through the existing 30-bit MIG interface
  new freechips.rocketchip.subsystem.WithoutTLMonitors)


class BoraLakeRocketConfig extends Config(
  
  new WithBoraLakeHDMI ++
  new chipyard.harness.WithSomeClocksFromHarnessClockInstantiator ++
  new chipyard.iobinders.WithHDMIPunchthrough ++
  new barduino_blocks.WithVideoScanout ++
  new chipyard.config.WithNoDebug ++ // no jtag
  new chipyard.config.WithNoUART ++
  new testchipip.serdes.WithNoSerialTL ++
  new chipyard.config.WithBroadcastManager ++ // no l2
  new WithBoraLakeTweaks(freqMHz = 100) ++
  new chipyard.RocketConfig)
