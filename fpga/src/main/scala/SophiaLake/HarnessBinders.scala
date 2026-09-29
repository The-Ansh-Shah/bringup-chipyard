package chipyard.fpga.sophialake

import chisel3._

import freechips.rocketchip.diplomacy.{LazyRawModuleImp}
import org.chipsalliance.diplomacy.nodes.{HeterogeneousBag}

import sifive.blocks.devices.uart.{UARTPortIO}
import sifive.fpgashells.shell._
import sifive.fpgashells.ip.xilinx._
import sifive.fpgashells.shell.xilinx._
import sifive.fpgashells.clocks._

import chipyard._
import chipyard.harness._
import chipyard.iobinders._
import testchipip.serdes._


//==========================================================
// Pin maps and constraint helpers
//==========================================================

/** Package pins for one 8-bit decoupled serial-TL port, in bit order. */
case class DecoupledSerialTLPins(
  clock:    String,
  outValid: String,
  outReady: String,
  outPhit:  Seq[String],
  inValid:  String,
  inReady:  String,
  inPhit:   Seq[String])

/** Package pins for one 1-bit credited source-synchronous C2C port. The two
  * boards use mirrored maps, since the PMOD cable runs straight through. */
case class CreditedC2CPins(
  clockOut:  String,
  resetOut:  String,
  outValid:  String,
  outPhit:   String,
  clockIn:   String,
  resetIn:   String,
  inValid:   String,
  inPhit:    String)

/** Package pins for an FT-LINK debugger (JTAG + UART), named from the FPGA's
  * side: the FPGA drives `tdo` and `uartTx`. */
case class FTLinkPins(
  tck:    String,
  tms:    String,
  tdi:    String,
  tdo:    String,
  uartRx: String,
  uartTx: String)

object SophiaLakePins {
  val dsp25ChipLink = DecoupledSerialTLPins(
    clock    = "A21",
    outValid = "B20", outReady = "E16",
    outPhit  = Seq("C22", "E19", "M15", "A15", "C13", "L15", "G20", "F13"),
    inValid  = "G13", inReady  = "E17",
    inPhit   = Seq("G15", "H14", "J20", "J17", "N20", "N19", "J19", "A14"))

  val bml25ChipLink = DecoupledSerialTLPins(
    clock    = "D17",
    outValid = "C15", outReady = "D16",
    outPhit  = Seq("C17", "A18", "B21", "A20", "B18", "A19", "C18", "B17"),
    inValid  = "A21", inReady  = "B20",
    inPhit   = Seq("H19", "G20", "C14", "H20", "F19", "E22", "C19", "C22"))

  /** DSP25 drives PMOD 00-01 and listens on PMOD 02-03. */
  val dsp25C2C = CreditedC2CPins(
    clockOut = "Y16",  resetOut = "AA16", outValid = "AB16", outPhit = "AB17",
    clockIn  = "AA13", resetIn  = "AB13", inValid  = "AA15", inPhit  = "AB15")

  /** BML25 is the mirror image of DSP25: it drives what DSP25 listens on. */
  val bml25C2C = CreditedC2CPins(
    clockOut = "AB15", resetOut = "AA15", outValid = "AB13", outPhit = "AA13",
    clockIn  = "AB17", resetIn  = "AB16", inValid  = "AA16", inPhit  = "Y16")

  /** An FT-LINK plugged into J7, the same PMOD the C2C configs use. J7 numbers
    * its pins from the power end (3V3 on 1-2, GND on 3-4) and the FT-LINK's H2
    * from the signal end, so with power lined up H2-1 (TDO) lands on J7-12.
    * nSRST (J7-7) and nTRST (J7-10) are left unconnected. */
  val ftLinkJ7 = FTLinkPins(
    tck    = "AA13",  // J7-8,  PM_02_P <- H2-5
    tms    = "AB17",  // J7-9,  PM_01_N <- H2-4
    tdi    = "AB16",  // J7-11, PM_01_P <- H2-2
    tdo    = "AA15",  // J7-12, PM_03_P -> H2-1
    uartRx = "AA16",  // J7-5,  PM_00_N <- H2-8 (FT-LINK TXD)
    uartTx = "AB13")  // J7-6,  PM_02_N -> H2-7 (FT-LINK RXD)

  /** Constrain each (package pin, IO) pair to `ioStandard`. */
  def addPins(
    ath: SophiaLakeHarness,
    pins: Seq[(String, IOPin)],
    ioStandard: String,
    iob: Boolean = true,
    pullup: Boolean = false
  ): Unit = pins.foreach { case (pin, io) =>
    ath.xdc.addPackagePin(io, pin)
    ath.xdc.addIOStandard(io, ioStandard)
    if (iob)    ath.xdc.addIOB(io)
    if (pullup) ath.xdc.addPullup(io)
  }

  /** Pin out a decoupled serial-TL port and constrain its forwarded clock. */
  def constrainChipLink(ath: SophiaLakeHarness, io: DecoupledPhitIO, pins: DecoupledSerialTLPins): Unit = {
    val clkIO = io match {
      case io: HasClockOut => IOPin(io.clock_out)
      case io: HasClockIn  => IOPin(io.clock_in)
    }
    val packagePinsWithPackageIOs =
      Seq(
        (pins.clock,    clkIO),
        (pins.outValid, IOPin(io.out.valid)),
        (pins.outReady, IOPin(io.out.ready))
      ) ++
      pins.outPhit.zipWithIndex.map { case (pin, i) => (pin, IOPin(io.out.bits.phit, i)) } ++
      Seq(
        (pins.inValid, IOPin(io.in.valid)),
        (pins.inReady, IOPin(io.in.ready))
      ) ++
      pins.inPhit.zipWithIndex.map { case (pin, i) => (pin, IOPin(io.in.bits.phit, i)) }

    addPins(ath, packagePinsWithPackageIOs, "LVCMOS12", iob = false)

    // Don't add IOB to the clock, if its an input
    val iobPins = io match {
      case _: DecoupledInternalSyncPhitIO => packagePinsWithPackageIOs
      case _: DecoupledExternalSyncPhitIO => packagePinsWithPackageIOs.drop(1)
    }
    iobPins.foreach { case (_, io) => ath.xdc.addIOB(io) }

    ath.sdc.addClock("ser_tl_clock", clkIO, 100)
    ath.sdc.addGroup(pins = Seq(clkIO))
    ath.xdc.clockDedicatedRouteFalse(clkIO)
  }

  /** Pin out a credited source-sync C2C port.
    *
    * The forwarded clock goes out INVERTED (180 deg) so the far side samples in
    * the middle of the data eye rather than on the launching edge, which buys
    * setup/hold margin on the slow PMOD link. Only the outgoing clock is
    * flipped; clock_in is left untouched (the peer inverts its own).
    */
  def constrainC2CLink(
    ath: SophiaLakeHarness,
    io: CreditedSourceSyncPhitIO,
    dutIO: CreditedSourceSyncPhitIO,
    pins: CreditedC2CPins
  ): Unit = {
    io.clock_out := (~dutIO.clock_out.asUInt).asBool.asClock

    addPins(ath, Seq(
      (pins.clockOut, IOPin(io.clock_out)),
      (pins.resetOut, IOPin(io.reset_out)),
      (pins.outValid, IOPin(io.out.valid)),
      (pins.outPhit,  IOPin(io.out.bits.phit, 0)),
      (pins.clockIn,  IOPin(io.clock_in)),
      (pins.resetIn,  IOPin(io.reset_in)),
      (pins.inValid,  IOPin(io.in.valid)),
      (pins.inPhit,   IOPin(io.in.bits.phit, 0))
    ), "LVCMOS33")

    ath.sdc.addClock("c2c_clock_in", IOPin(io.clock_in), 100)
    ath.sdc.addGroup(pins = Seq(IOPin(io.clock_in)))
    ath.xdc.clockDedicatedRouteFalse(IOPin(io.clock_in))
  }

  def harness(th: HasHarnessInstantiators): SophiaLakeHarness =
    th.asInstanceOf[LazyRawModuleImp].wrapper.asInstanceOf[SophiaLakeHarness]
}


//==========================================================
// Serial-TL to GPIO binders
//==========================================================

class WithDSP25SophiaLakeSerialTLToGPIO extends HarnessBinder({
  case (th: HasHarnessInstantiators, port: SerialTLPort, chipId: Int) => {
    val ath = SophiaLakePins.harness(th)
    val harnessIO = IO(chiselTypeOf(port.io)).suggestName("serial_tl")
    harnessIO <> port.io
    harnessIO match {
      case io: DecoupledPhitIO => SophiaLakePins.constrainChipLink(ath, io, SophiaLakePins.dsp25ChipLink)
    }
  }
})


class WithBML25SophiaLakeSerialTLToGPIO extends HarnessBinder({
  case (th: HasHarnessInstantiators, port: SerialTLPort, chipId: Int) => {
    val ath = SophiaLakePins.harness(th)
    val harnessIO = IO(chiselTypeOf(port.io)).suggestName("serial_tl")
    harnessIO <> port.io
    harnessIO match {
      case io: DecoupledPhitIO => SophiaLakePins.constrainChipLink(ath, io, SophiaLakePins.bml25ChipLink)
    }
  }
})


/** C2C designs have two ports: port 0 is the decoupled link to our own chip
  * (same pins as the single-chip config), port 1 the credited source-sync link
  * to the peer FPGA over the PMOD. */
class WithDSP25C2CSophiaLakeSerialTLToGPIO extends HarnessBinder({
  case (th: HasHarnessInstantiators, port: SerialTLPort, chipId: Int) => {
    val ath = SophiaLakePins.harness(th)
    val harnessIO = IO(chiselTypeOf(port.io)).suggestName(s"serial_tl_${port.portId}")
    harnessIO <> port.io
    harnessIO match {
      case io: DecoupledPhitIO if port.portId == 0 =>
        SophiaLakePins.constrainChipLink(ath, io, SophiaLakePins.dsp25ChipLink)
      case io: CreditedSourceSyncPhitIO if port.portId == 1 =>
        SophiaLakePins.constrainC2CLink(ath, io,
          port.io.asInstanceOf[CreditedSourceSyncPhitIO], SophiaLakePins.dsp25C2C)
    }
  }
})


class WithBML25C2CSophiaLakeSerialTLToGPIO extends HarnessBinder({
  case (th: HasHarnessInstantiators, port: SerialTLPort, chipId: Int) => {
    val ath = SophiaLakePins.harness(th)
    val harnessIO = IO(chiselTypeOf(port.io)).suggestName(s"serial_tl_${port.portId}")
    harnessIO <> port.io
    harnessIO match {
      case io: DecoupledPhitIO if port.portId == 0 =>
        SophiaLakePins.constrainChipLink(ath, io, SophiaLakePins.bml25ChipLink)
      case io: CreditedSourceSyncPhitIO if port.portId == 1 =>
        SophiaLakePins.constrainC2CLink(ath, io,
          port.io.asInstanceOf[CreditedSourceSyncPhitIO], SophiaLakePins.bml25C2C)
    }
  }
})


//==========================================================
// Bringup link binders
//==========================================================

/** TSI over the on-board FTDI USB-UART. */
class WithSophiaLakeUARTTSI extends HarnessBinder({
  case (th: HasHarnessInstantiators, port: UARTTSIPort, chipId: Int) => {
    val ath = SophiaLakePins.harness(th)
    val harnessIO = IO(new UARTPortIO(port.io.uartParams)).suggestName("uart_tsi")
    harnessIO <> port.io.uart

    SophiaLakePins.addPins(ath, Seq(
      ("T21", IOPin(harnessIO.rxd)),
      ("U21", IOPin(harnessIO.txd))
    ), "LVCMOS33")
  }
})

/** JTAG on an FT-LINK. PMOD pins aren't clock-capable, so TCK is routed over
  * the general fabric. */
class WithSophiaLakeJTAG(pins: FTLinkPins = SophiaLakePins.ftLinkJ7) extends HarnessBinder({
  case (th: HasHarnessInstantiators, port: JTAGPort, chipId: Int) => {
    val ath = SophiaLakePins.harness(th)
    val harnessIO = IO(new JTAGChipIO(false)).suggestName("jtag")
    harnessIO.TDO := port.io.TDO
    port.io.TCK := harnessIO.TCK
    port.io.TDI := harnessIO.TDI
    port.io.TMS := harnessIO.TMS
    port.io.reset.foreach(_ := th.referenceReset)

    SophiaLakePins.addPins(ath, Seq(
      (pins.tck, IOPin(harnessIO.TCK)),
      (pins.tms, IOPin(harnessIO.TMS)),
      (pins.tdi, IOPin(harnessIO.TDI)),
      (pins.tdo, IOPin(harnessIO.TDO))
    ), "LVCMOS33", iob = false, pullup = true)

    ath.sdc.addClock("JTCK", IOPin(harnessIO.TCK), 10)
    ath.sdc.addGroup(clocks = Seq("JTCK"))
    ath.xdc.clockDedicatedRouteFalse(IOPin(harnessIO.TCK))
  }
})

/** The SoC's UART on an FT-LINK; the on-board FTDI UART carries UART-TSI. The
  * pull-ups hold rxd idle when no FT-LINK is plugged in. */
class WithSophiaLakeUART(pins: FTLinkPins = SophiaLakePins.ftLinkJ7) extends HarnessBinder({
  case (th: HasHarnessInstantiators, port: UARTPort, chipId: Int) => {
    val ath = SophiaLakePins.harness(th)
    val harnessIO = IO(chiselTypeOf(port.io)).suggestName("uart")
    harnessIO <> port.io

    SophiaLakePins.addPins(ath, Seq(
      (pins.uartRx, IOPin(harnessIO.rxd)),
      (pins.uartTx, IOPin(harnessIO.txd))
    ), "LVCMOS33", pullup = true)
  }
})


//==========================================================
// Peripheral binders
//==========================================================

/** Route the design's TileLink memory port to the shell's DDR client. */
class WithSophiaLakeTDDRTL extends HarnessBinder({
  case (th: HasHarnessInstantiators, port: TLMemPort, chipId: Int) => {
    val ath = SophiaLakePins.harness(th)
    val bundles = ath.ddrClient.out.map(_._1)
    val ddrClientBundle = Wire(new HeterogeneousBag(bundles.map(_.cloneType)))
    bundles.zip(ddrClientBundle).foreach { case (bundle, io) => bundle <> io }
    ddrClientBundle <> port.io
  }
})

/** The SoC's 4-pin GPIO block on the board's spare LEDs and push buttons.
  * LEDs 0-1 (bi-color LED20) stay the harness's status LEDs, so the pins map
  * onto bi-color LED21 and the two buttons:
  *   pin 0 -> LED 2, LED21 green (P19)
  *   pin 1 -> LED 3, LED21 red   (U20)
  *   pin 2 <- button 0, SW8      (Y19)
  *   pin 3 <- button 1, SW9      (Y21)
  * The LEDs are common-anode on 3V3, so they light when their pin is driven
  * low; the binder inverts, so a GPIO 1 means lit. The buttons switch to 3V3
  * over a 10k pull-down, so pressed reads 1 and no internal pull is needed.
  *
  * The pins behave like real pads: an LED lights only while its output_en
  * bit is set, and input_val follows a pin only while its input_en bit is
  * set (an LED pin reads back the level it drives). */
class WithSophiaLakeGPIO extends HarnessBinder({
  case (th: HasHarnessInstantiators, port: GPIOPinsPort, chipId: Int) => {
    val ath = SophiaLakePins.harness(th)
    val pins = port.io.pins
    require(port.gpioId == 0 && pins.size == 4,
      s"WithSophiaLakeGPIO maps a single 4-pin GPIO block, got gpio_${port.gpioId} with ${pins.size} pins")

    val buttons = IO(Input(UInt(2.W))).suggestName("gpio_button")
    SophiaLakePins.addPins(ath, Seq(
      ("Y19", IOPin(buttons, 0)),
      ("Y21", IOPin(buttons, 1))
    ), "LVCMOS33", iob = false)

    val leds = ath.all_leds.drop(2).map(_.getWrappedValue)
    leds.zip(pins.take(2)).foreach { case (led, pin) =>
      val level = pin.o.oval && pin.o.oe
      led := !level                                                     // active-low LED
      pin.i.ival := level && pin.o.ie
    }
    buttons.asBools.zip(pins.drop(2)).foreach { case (button, pin) =>
      pin.i.ival := button && pin.o.ie
    }
    pins.foreach(_.i.po.foreach(_ := DontCare))
  }
})
