package chipyard
 
import freechips.rocketchip.diplomacy.AddressSet
import saturn.common._
import atlas.config._
import freechips.rocketchip.resources.BigIntHexContext
import freechips.rocketchip.subsystem._
import freechips.rocketchip.diplomacy.RegionType
import org.chipsalliance.cde.config.{Config, Parameters}
import testchipip.soc.{OBUS, WithMbusScratchpad}

class EE290SimConfig extends Config(
  new WithAtlasTile() ++
  new tacit.WithTraceSinkDMA(1) ++
  new tacit.WithTraceSinkAlways(0) ++
  new chipyard.config.WithTraceArbiterMonitor ++
  new chipyard.WithTacitEncoder ++
  new saturn.shuttle.WithShuttleVectorUnit(256, 128, VectorParams.mxParams) ++
  new chipyard.config.WithSystemBusWidth(256) ++
  new shuttle.common.WithShuttleTileBeatBytes(16) ++
  new shuttle.common.WithNShuttleCores(1) ++
  new chipyard.config.WithBroadcastManager ++
  new freechips.rocketchip.subsystem.WithCacheBlockBytes(64) ++
  new EE290BaseConfig
)

class EE290SimPeripheralConfig extends Config(
  new WithAtlasTile() ++
  new WithEE290TapeoutPeripherals() ++
  new saturn.shuttle.WithShuttleVectorUnit(256, 128, VectorParams.mxParams) ++
  new chipyard.config.WithSystemBusWidth(256) ++
  new shuttle.common.WithShuttleTileBeatBytes(16) ++
  new shuttle.common.WithNShuttleCores(1) ++
  new chipyard.config.WithBroadcastManager ++
  new freechips.rocketchip.subsystem.WithCacheBlockBytes(64) ++
  new EE290BaseConfig
)

class EE290SimConfigFast extends Config(
  new WithAtlasTile() ++
  new saturn.shuttle.WithShuttleVectorUnit(256, 128, VectorParams.mxParams) ++
  new chipyard.config.WithSystemBusWidth(256) ++
  new shuttle.common.WithShuttleTileBeatBytes(16) ++
  new shuttle.common.WithNShuttleCores(1) ++
  new freechips.rocketchip.subsystem.WithCacheBlockBytes(64) ++
  new EE290BaseConfig
)

class WithEE290TapeoutPeripherals extends Config(
  new testchipip.serdes.WithSerialTL(Seq(
    testchipip.serdes.SerialTLParams(                               // 0th serial-tl is chip-to-bringup-fpga
      manager = Some(testchipip.serdes.SerialTLManagerParams(       // port acts as a manager of offchip memory
        memParams = Seq(
          testchipip.serdes.ManagerRAMParams(
            address    = BigInt("40000000", 16),                   // 1 GB uncached peripherals region (Atlas)
            size       = BigInt("40000000", 16),
            regionType = RegionType.IDEMPOTENT
          ),
          testchipip.serdes.ManagerRAMParams(                       // 32 GB of off-chip memory
            address = BigInt("80000000", 16),
            size    = BigInt("800000000", 16)
          ),
          testchipip.serdes.ManagerRAMParams(                       // 30 GB uncached peripherals region (Saturn)
            address    = BigInt("880000000", 16),
            size       = BigInt("780000000", 16),
            regionType = RegionType.IDEMPOTENT
          )
        ),
        isMemoryDevice = true,
        slaveWhere = MBUS
      )),
      client = Some(testchipip.serdes.SerialTLClientParams()),      // bringup serial-tl acts only as a client
      phyParams = testchipip.serdes.DecoupledExternalSyncSerialPhyParams(
        phitWidth = 32,
        flitWidth = 32,
      ),  // bringup serial-tl is sync'd to external clock
    ),
  )) ++
  new freechips.rocketchip.subsystem.WithNoMemPort
)


// Keep the bringup link and buses at one frequency; existing callers default to 500 MHz.
class EE290BringupHostConfig(freqMHz: Int = 500, tsiToChip: Boolean = false) extends Config(
  //=============================
  // Set up TestHarness for standalone-sim
  //=============================
  new chipyard.harness.WithAbsoluteFreqHarnessClockInstantiator ++  // Generate absolute frequencies
  new chipyard.harness.WithSerialTLTiedOff ++                       // when doing standalone sim, tie off the serial-tl port
  new chipyard.harness.WithSimTSIToUARTTSI ++                       // Attach SimTSI-over-UART to the UART-TSI port
  new chipyard.iobinders.WithSerialTLPunchthrough ++                // Don't generate IOCells for the serial TL (this design maps to FPGA)

  //=============================
  // Scratchpads to mimic address space
  //=============================

  new WithMbusScratchpad(base = BigInt("40000000", 16), size = (1 << 10)) ++
  new WithMbusScratchpad(base = BigInt("880000000", 16), size = (1 << 10)) ++

  //=============================
  // Setup the SerialTL side on the bringup device
  //=============================
  new testchipip.serdes.WithSerialTL(Seq(testchipip.serdes.SerialTLParams(
    manager = Some(testchipip.serdes.SerialTLManagerParams(
      memParams = Seq(testchipip.serdes.ManagerRAMParams(                            // Tethered TSI must also reach cached chip memory
        address = BigInt("00000000", 16),
        size    = if (tsiToChip) BigInt("1000000000", 16) else BigInt("40000000", 16)
      ))
    )),
    client = Some(testchipip.serdes.SerialTLClientParams()),                                        // Allow chip to access this device's memory (DRAM)
    phyParams = testchipip.serdes.DecoupledInternalSyncSerialPhyParams(phitWidth=32, flitWidth=32, freqMHz = freqMHz) // bringup platform provides the clock
  ))) ++

  //============================
  // Setup bus topology on the bringup system
  //============================
  // In tethered mode UART-TSI alone masters OBUS, so all HTIF accesses enter
  // the chip's coherent FBUS. Chip memory traffic returns through the host FBUS
  // to local DRAM, without a path back to OBUS.
  (if (tsiToChip) new Config((site, here, up) => {
    case testchipip.tsi.UARTTSIClientKey => up(testchipip.tsi.UARTTSIClientKey).map(_.copy(tlbus = OBUS))
    case TLNetworkTopologyLocated(InSubsystem) => up(TLNetworkTopologyLocated(InSubsystem)) :+
      new freechips.rocketchip.tilelink.TLBusWrapperTopology(Nil, Nil) {
        override def connect(context: HasTileLinkLocations)(implicit p: Parameters): Unit = {
          // Preserve the OBUS clock connection without an SBUS TileLink path.
          context.locateTLBusWrapper(OBUS).clockGroupNode := context.locateTLBusWrapper(SBUS).clockGroupNode
        }
      }
  }) else new testchipip.soc.WithOffchipBusClient(SBUS,                                // offchip bus hangs off the SBUS
    blockRange = AddressSet.misaligned(0x80000000L, (BigInt(1) << 30) * 4))) ++ // offchip bus should not see the main memory of the testchip, since that can be accessed directly
  new testchipip.soc.WithOffchipBus ++                                         // offchip bus

  //=============================
  // Set up memory on the bringup system
  //=============================
  new freechips.rocketchip.subsystem.WithExtMemSize((1 << 30) * 16L) ++         // match what the chip believes the max size should be

  //=============================
  // Generate the TSI-over-UART side of the bringup system
  //=============================
  new testchipip.tsi.WithUARTTSIClient(initBaudRate = BigInt(921600)) ++       // nonstandard baud rate to improve performance

  //=============================
  // Set up clocks of the bringup system
  //=============================
  new chipyard.clocking.WithPassthroughClockGenerator ++ // pass all the clocks through, since this isn't a chip
  new chipyard.harness.WithHarnessBinderClockFreqMHz(freqMHz) ++
  new chipyard.config.WithUniformBusFrequencies(freqMHz) ++ // match the bus clocks to the SerialTL clock

  // Base is the no-cores config
  new chipyard.NoCoresConfig)


class TetheredEE290Config extends Config(
  // The simulated UART adapter and the host UART must use the same clock rate.
  new chipyard.harness.WithHarnessBinderClockFreqMHz(500.0) ++
  new chipyard.harness.WithAbsoluteFreqHarnessClockInstantiator ++   // use absolute freqs for sims in the harness
  new chipyard.harness.WithMultiChipSerialTL(0, 1) ++                // connect the serial-tl ports of the chips together
  new chipyard.harness.WithMultiChip(0, new EE290SimPeripheralConfig) ++ // ChipTop0 is the design-to-be-taped-out
  new chipyard.harness.WithMultiChip(1, new EE290BringupHostConfig(tsiToChip = true)))  // ChipTop1 is the bringup design

class EE290BaseConfig extends Config(
  new chipyard.config.WithSystemBusWidth(bitWidth = 256) ++
  new freechips.rocketchip.subsystem.WithExtMemSize(x"10_0000_0000") ++
  new freechips.rocketchip.subsystem.WithCacheBlockBytes(32) ++
  new freechips.rocketchip.subsystem.WithNMemoryChannels(1) ++
  new freechips.rocketchip.subsystem.WithEdgeDataBits(64) ++

  new chipyard.config.WithPeripheryBusFrequency(500.0) ++
  new chipyard.config.WithMemoryBusFrequency(500.0) ++
  new chipyard.config.WithControlBusFrequency(500.0) ++
  new chipyard.config.WithSystemBusFrequency(500.0) ++
  new chipyard.config.WithFrontBusFrequency(500.0) ++
  new chipyard.config.WithOffchipBusFrequency(500.0) ++
  new chipyard.harness.WithHarnessBinderClockFreqMHz(500.0) ++
  new testchipip.boot.WithNoCustomBootPin ++
  new chipyard.config.AbstractConfig)
