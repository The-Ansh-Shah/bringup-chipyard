package chipyard.fpga.vcu118

import org.chipsalliance.cde.config.Config
import freechips.rocketchip.subsystem.ExtMem
import sifive.fpgashells.shell.xilinx.VCU118DDRSize
import testchipip.serdes.SerialTLKey
import testchipip.tsi.UARTTSIClientKey
import chipyard._
import chipyard.harness._

// Both systems are emulated on one VCU118. SerialTL stays entirely internal;
// only the host owns DDR and USB-UART. No simulation clock or TSI models.
class TetheredEE290VCU118Config extends Config(
  new WithMultiChipSerialTL(0, 1) ++
  new WithMultiChip(0, new Config(
    new WithVCU118UARTToPMOD() ++
    new WithJTAG ++
    new WithAllClocksFromHarnessClockInstantiator ++
    new chipyard.iobinders.WithSerialTLPunchthrough ++
    new chipyard.config.WithUniformBusFrequencies(50.0) ++
    new WithHarnessBinderClockFreqMHz(50.0) ++
    new Config((site, here, up) => {
      case SerialTLKey => up(SerialTLKey).map(s => s.copy(manager = s.manager.map(m =>
        m.copy(memParams = m.memParams.map(r =>
          if (r.address == BigInt("80000000", 16)) r.copy(size = site(VCU118DDRSize)) else r)))))
    }) ++
    new EE290SimPeripheralConfig)) ++
  new WithMultiChip(1, new Config(
    new WithVCU118UARTTSI ++
    new WithDDRMem ++
    new WithAllClocksFromHarnessClockInstantiator ++
    new chipyard.config.WithTLBackingMemory ++
    new Config((site, here, up) => {
      case ExtMem => up(ExtMem).map(m => m.copy(master = m.master.copy(size = site(VCU118DDRSize))))
      case UARTTSIClientKey => up(UARTTSIClientKey).map(u =>
        u.copy(uartParams = u.uartParams.copy(initBaudRate = BigInt(115200))))
    }) ++
    new EE290BringupHostConfig(freqMHz = 50, tsiToChip = true))) ++
  // The shell uses top-level parameters to place its overlays. Match the
  // host's memory interface and supply UART pad parameters without a third DUT.
  new WithoutSDIO ++
  new WithDefaultPeripherals ++
  new WithFPGAFrequency(50.0) ++
  new chipyard.config.WithUniformBusFrequencies(50.0) ++
  new WithVCU118Tweaks ++
  new NoCoresConfig)
