package chipyard

import org.chipsalliance.cde.config.Config

// Simulation-only smoke-test variant. Keep the original tethered topology,
// but avoid spending most of the run bit-banging the 921600-baud UART loader.
// Both UART endpoints derive 12.5 Mbaud from a 500 MHz clock (divisor 40).
// This is not a board UART setting or an FPGA-ready configuration.
class TetheredEE290FastUARTConfig extends Config(
  new chipyard.harness.WithHarnessBinderClockFreqMHz(500.0) ++
  new chipyard.harness.WithAbsoluteFreqHarnessClockInstantiator ++
  new chipyard.harness.WithMultiChipSerialTL(0, 1) ++
  new chipyard.harness.WithMultiChip(0, new EE290SimPeripheralConfig) ++
  new chipyard.harness.WithMultiChip(1, new Config(
    new Config((site, here, up) => {
      case testchipip.tsi.UARTTSIClientKey => up(testchipip.tsi.UARTTSIClientKey).map(p =>
        p.copy(uartParams = p.uartParams.copy(initBaudRate = BigInt(12500000))))
    }) ++
    new EE290BringupHostConfig(freqMHz = 500, tsiToChip = true))))
