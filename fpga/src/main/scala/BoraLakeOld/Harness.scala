package chipyard.fpga.boralakeold

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy._
import org.chipsalliance.cde.config.{Parameters}
import freechips.rocketchip.tilelink._
import freechips.rocketchip.prci._
import freechips.rocketchip.subsystem.{SystemBusKey}

import sifive.fpgashells.shell.xilinx._
import sifive.fpgashells.shell._
import sifive.fpgashells.clocks._
import sifive.fpgashells.ip.xilinx.{IBUF, PowerOnResetFPGAOnly}

import sifive.blocks.devices.uart._

import chipyard._
import chipyard.harness._

import testchipip.serdes.SerialTLKey

class BoraLakeHarness(override implicit val p: Parameters) extends BoraLakeShell {
  def dp = designParameters

  val clockOverlay = dp(ClockInputOverlayKey).map(_.place(ClockInputDesignInput())).head
  val harnessSysPLL = dp(PLLFactoryKey)
  val harnessSysPLLNode = harnessSysPLL()
  val dutFreqMHz = (dp(SystemBusKey).dtsFrequency.get / (1000 * 1000)).toInt
  val dutClock = ClockSinkNode(freqMHz = dutFreqMHz)
  println(s"Bora Lake FPGA Base Clock Freq: ${dutFreqMHz} MHz")
  val dutWrangler = LazyModule(new ResetWrangler())
  // val harnessEphemeralNode = ClockGroup()
  val dutGroup = ClockGroup()
  dutClock := dutWrangler.node := dutGroup := harnessSysPLLNode
  
  val videoPLLSrcNode = ClockSourceNode(freqMHz = 50, jitterPS = 50)
  val videoPLL = dp(PLLFactoryKey)
  val videoPLLNode = videoPLL()
  val hdmiClockGroup = ClockGroup()
  val pxClock   = ClockSinkNode(freqMHz = 25.175)
  val pxClockx5 = ClockSinkNode(freqMHz = 125.875)

  // videoPLLNode.in.head._1.clock := clockOverlay.overlayOutput.node.out.head._1.clock
  pxClock   := hdmiClockGroup := videoPLLNode := videoPLLSrcNode
  pxClockx5 := hdmiClockGroup

  harnessSysPLLNode := clockOverlay.overlayOutput.node

  val ddrOverlay = dp(DDROverlayKey).head.place(DDRDesignInput(dp(ExtTLMem).get.master.base, dutWrangler.node, harnessSysPLLNode)).asInstanceOf[DDRBoraLakePlacedOverlay]
  val ddrClient = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(TLMasterParameters.v1(
    name = "chip_ddr",
    sourceId = IdRange(0, 1 << dp(ExtTLMem).get.master.idBits)
  )))))
  val ddrBlockDuringReset = LazyModule(new TLBlockDuringReset(4))
  ddrOverlay.overlayOutput.ddr := ddrBlockDuringReset.node := ddrClient


  // D24/D23 remain dedicated to UART-TSI; do not place their LED overlays.
  val ledOverlays = dp(LEDOverlayKey).drop(2).map(_.place(LEDDesignInput()))
  val all_leds = ledOverlays.map(_.overlayOutput.led)
  val status_leds = all_leds.take(7)
  println(s"Status leds: ${status_leds}, all leds: ${all_leds}")

  override lazy val module = new HarnessLikeImpl

  class HarnessLikeImpl extends Impl with HasHarnessInstantiators {
    all_leds.foreach(_ := DontCare)
    clockOverlay.overlayOutput.node.out(0)._1.reset := ~resetPin

    val boardClock = clockOverlay.overlayOutput.node.out.head._1.clock
    videoPLLSrcNode.out.head._1.clock := boardClock
    videoPLLSrcNode.out.head._1.reset := pllReset

    // Blink the status LEDs for sanity
    val blinky_rst = 0.B
    withClockAndReset(boardClock, dutClock.in.head._1.reset) {
      val period = (BigInt(100) << 20) / 2
      val counter = RegInit(0.U(log2Ceil(period).W))
      val pulse = RegInit(0.U(1.W))
      counter := Mux(counter === (period-1).U, 0.U, counter + 1.U)
      when (counter === 0.U) {
        pulse := ~pulse
      }

      status_leds(0) := pulse       // Green
      status_leds(1) := resetPin    // Red

    }

    
    harnessSysPLL.plls.foreach(_._1.getReset.get := pllReset)

    def referenceClockFreqMHz = dutFreqMHz
    def referenceClock = dutClock.in.head._1.clock
    def referenceReset = dutClock.in.head._1.reset.asBool ||
      pxClock.in.head._1.reset.asBool || pxClockx5.in.head._1.reset.asBool
    def success = { require(false, "Unused"); false.B }

    // override def clockNode = Some(harnessEphemeralNode)

    childClock := harnessBinderClock
    childReset := harnessBinderReset


    ddrOverlay.mig.module.clock := harnessBinderClock
    ddrOverlay.mig.module.reset := harnessBinderReset
    ddrBlockDuringReset.module.clock := harnessBinderClock
    val calibrationReset = freechips.rocketchip.util.ResetCatchAndSync(
      harnessBinderClock, !ddrOverlay.mig.module.io.port.init_calib_complete)
    ddrBlockDuringReset.module.reset := harnessBinderReset.asBool || calibrationReset

    instantiateChipTops()

    harnessClockInstantiator.clockMap("clock_25.175MHz")._2 := pxClock.in.head._1.clock
    harnessClockInstantiator.clockMap("clock_125.875MHz")._2 := pxClockx5.in.head._1.clock
  }
}
