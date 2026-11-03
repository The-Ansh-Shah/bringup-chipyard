package barduino_blocks

import chisel3._
import chisel3.util._
import chisel3.experimental.{IntParam, BaseModule}
import freechips.rocketchip.prci._
import freechips.rocketchip.subsystem.{BaseSubsystem, PBUS, SBUS}
import org.chipsalliance.cde.config.{Parameters, Field, Config}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.regmapper.{HasRegMap, RegField}
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util.UIntIsOneOf
import freechips.rocketchip.util.{AsyncQueue, AsyncQueueParams}
import freechips.rocketchip.util.{SynchronizerShiftReg}

case class VideoScanoutKeyParams(
  address: BigInt = 0x10081000
)

case object VideoScanoutKey extends Field[Option[VideoScanoutKeyParams]](None)

class HDMIVideoIO extends Bundle {
  val TMDS_CLK_P    = Output(Bool())
  val TMDS_CLK_N    = Output(Bool())
  val TMDS_DATA_P   = Output(UInt(3.W))
  val TMDS_DATA_N   = Output(UInt(3.W))
}

class VideoScanoutIO extends Bundle {
  val hdmiOut = new HDMIVideoIO()
  val pxclk = Input(Clock())
  val pxclkx5 = Input(Clock())
  val videoReset = Input(Bool())
}

class hdmi_tx_v1_0 extends BlackBox with HasBlackBoxResource {
  val io = IO(new Bundle {
    val pix_clk   = Input(Clock())
    val pix_clkx5 = Input(Clock())
    val rst       = Input(Reset())
    val red       = Input(UInt(8.W))
    val green     = Input(UInt(8.W))
    val blue      = Input(UInt(8.W))
    val hsync     = Input(Bool())
    val vsync     = Input(Bool())
    val vde       = Input(Bool())
    val aux0_din  = Input(UInt(4.W))
    val aux1_din  = Input(UInt(4.W))
    val aux2_din  = Input(UInt(4.W))
    val ade       = Input(Bool())
    val TMDS_CLK_P    = Output(Bool())
    val TMDS_CLK_N    = Output(Bool())
    val TMDS_DATA_P   = Output(UInt(3.W))
    val TMDS_DATA_N   = Output(UInt(3.W))
  })
  addResource("/vsrc/hdmi_tx_v1_0.v")
}

class VideoStatusSynchronizer extends BlackBox with HasBlackBoxResource {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())
    val d = Input(Bool())
    val q = Output(Bool())
  })
  addResource("/vsrc/VideoStatusSynchronizer.v")
}

class ScanoutWord extends Bundle {
  val data = UInt(64.W)
  val word = UInt(17.W)
  val frame = UInt(8.W)
}

class VideoScanout(params: VideoScanoutKeyParams, beatBytes: Int)(implicit p: Parameters)
    extends ClockSinkDomain(ClockSinkParameters())(p) {
  val device = new SimpleDevice("video_scanout", Seq("barduino,video_scanout"))
  val node = TLRegisterNode(Seq(AddressSet(params.address, 4095)), device, "reg/control", beatBytes=beatBytes)
  val dma_node = TLClientNode(Seq(TLMasterPortParameters.v1(
    clients=Seq(TLMasterParameters.v1(name="video-scanout", sourceId=IdRange(0, 1))), minLatency=1)))

  override lazy val module = new VideoScanoutImpl
  class VideoScanoutImpl extends Impl {
    val io = IO(new VideoScanoutIO)
    withClockAndReset(clock, reset) {
    require(beatBytes == 8, "Scanout requires a 64-bit bus")
    // Software may change base only with enable=0 and busy=0. Base is 64-byte aligned.
    val enabled = RegInit(false.B)
    val base = RegInit(0x81000000L.U(64.W))
    val idle :: request :: response :: Nil = Enum(3)
    val state = RegInit(idle)
    val burstWord = RegInit(0.U(17.W))
    val dmaFrame = RegInit(0.U(8.W))
    val beat = RegInit(0.U(3.W))
    val dmaError = RegInit(false.B)
    val runReset = reset.asBool || io.videoReset || !enabled
    val sourceReset = freechips.rocketchip.util.ResetCatchAndSync(clock, runReset)
    val pixelReset = freechips.rocketchip.util.ResetCatchAndSync(io.pxclk, runReset)
    val fifo = Module(new AsyncQueue(new ScanoutWord, AsyncQueueParams(depth=64)))
    fifo.io.enq_clock := clock
    fifo.io.enq_reset := sourceReset
    fifo.io.deq_clock := io.pxclk
    fifo.io.deq_reset := pixelReset

    val pixelVsync = Wire(Bool())
    val pixelUnderflow = Wire(Bool())
    withClockAndReset(io.pxclk, pixelReset) {
      val h = RegInit(0.U(10.W))
      val v = RegInit(0.U(10.W))
      val frame = RegInit(0.U(8.W))
      val word = RegInit(0.U(17.W))
      val lane = RegInit(0.U(2.W))
      val underflow = RegInit(false.B)
      val active = h >= 144.U && h < 784.U && v >= 35.U && v < 515.U
      val vsync = v < 2.U
      pixelVsync := vsync
      pixelUnderflow := underflow
      when(h === 799.U) {
        h := 0.U
        when(v === 524.U) { v := 0.U; frame := frame + 1.U; word := 0.U }
          .otherwise { v := v + 1.U }
      }.otherwise { h := h + 1.U }
      val tag = fifo.io.deq.bits
      val matching = tag.frame === frame && tag.word === word
      // Drop late data at up to one word/pixel clock. Hold future-frame data.
      val frameDelta = tag.frame - frame
      val stale = frameDelta(7) || (tag.frame === frame && tag.word < word)
      fifo.io.deq.ready := stale || (active && lane === 3.U && matching)
      val validPixel = active && fifo.io.deq.valid && matching
      when(vsync) { underflow := false.B }
      when(active) {
        lane := lane + 1.U
        when(lane === 3.U) { word := word + 1.U }
        when(!validPixel) { underflow := true.B }
      }
      val pixel = (tag.data >> (lane << 4))(15, 0)
      val tx = Module(new hdmi_tx_v1_0)
      tx.io.pix_clk := io.pxclk
      tx.io.pix_clkx5 := io.pxclkx5
      tx.io.rst := pixelReset
      tx.io.red := Mux(validPixel, Cat(pixel(4,0), pixel(4,2)), 0.U)
      tx.io.green := Mux(validPixel, Cat(pixel(10,5), pixel(10,9)), 0.U)
      tx.io.blue := Mux(validPixel, Cat(pixel(15,11), pixel(15,13)), 0.U)
      // VGA 640x480@60 uses negative sync polarity.
      tx.io.hsync := !(h < 96.U)
      tx.io.vsync := !vsync
      tx.io.vde := active
      tx.io.aux0_din := 0.U; tx.io.aux1_din := 0.U; tx.io.aux2_din := 0.U; tx.io.ade := false.B
      io.hdmiOut.TMDS_CLK_P := tx.io.TMDS_CLK_P
      io.hdmiOut.TMDS_CLK_N := tx.io.TMDS_CLK_N
      io.hdmiOut.TMDS_DATA_P := tx.io.TMDS_DATA_P
      io.hdmiOut.TMDS_DATA_N := tx.io.TMDS_DATA_N
    }

    def statusSync(in: Bool): Bool = {
      val sync = Module(new VideoStatusSynchronizer)
      sync.io.clock := clock
      sync.io.reset := reset.asBool
      sync.io.d := in
      sync.io.q
    }
    val vsyncStatus = statusSync(pixelVsync)
    val underflowStatus = statusSync(pixelUnderflow)
    val videoReady = !statusSync(io.videoReset)
    val (tl, edge) = dma_node.out(0)
    tl.a.valid := state === request
    tl.a.bits := edge.Get(0.U, base + (burstWord << 3), 6.U)._2
    tl.d.ready := !enabled || sourceReset || fifo.io.enq.ready
    fifo.io.enq.valid := state === response && tl.d.valid && enabled && !sourceReset
    fifo.io.enq.bits.data := tl.d.bits.data
    fifo.io.enq.bits.word := burstWord + beat
    fifo.io.enq.bits.frame := dmaFrame
    when(state === idle) {
      when(!enabled) { burstWord := 0.U; dmaFrame := 0.U; dmaError := false.B }
      when(enabled && !sourceReset && fifo.io.enq.ready) { state := request; beat := 0.U }
    }
    when(state === request && tl.a.fire) { state := response }
    when(state === response && tl.d.fire) {
      when(tl.d.bits.denied || tl.d.bits.corrupt) { dmaError := true.B }
      beat := beat + 1.U
      when(beat === 7.U) {
        state := idle
        when(burstWord === (76800-8).U) { burstWord := 0.U; dmaFrame := dmaFrame + 1.U }
          .otherwise { burstWord := burstWord + 8.U }
      }
    }
    // 0x00/0x04 preserve the original status offsets; remaining registers are new.
    node.regmap(
      0x00 -> Seq(RegField.r(1, vsyncStatus)),
      0x04 -> Seq(RegField.r(1, underflowStatus)),
      0x08 -> Seq(RegField(1, enabled)),
      0x0c -> Seq(RegField.r(1, state =/= idle), RegField.r(1, videoReady), RegField.r(1, dmaError)),
      0x10 -> Seq(RegField(64, base)))
    }
  }
}

trait CanHaveVideoScanout { this: BaseSubsystem =>
  private val portName = "scanout"

  private val pbus = locateTLBusWrapper(PBUS)
  private val sbus = locateTLBusWrapper(SBUS)

  // Only build if we are using the TL (nonAXI4) version
  val hdmi_out = p(VideoScanoutKey) match {
    case Some(params) => {
      val scanout = LazyModule(new VideoScanout(params, pbus.beatBytes)(p))
      scanout.clockNode := pbus.fixedClockNode
      pbus.coupleTo(portName) { scanout.node := TLFragmenter(pbus.beatBytes, pbus.blockBytes) := _ }

      sbus.coupleFrom("scanout-dma") { _ := TLBuffer(16) := scanout.dma_node  }

      val hdmiGroup = ClockGroup()
      val pxClockNode = ClockSinkNode(freqMHz = 25.175)
      val pxClockx5Node = ClockSinkNode(freqMHz = 125.875)

      pxClockNode := hdmiGroup := allClockGroupsNode
      pxClockx5Node := hdmiGroup

      val hdmi = InModuleBody {
        val hdmi = IO(new HDMIVideoIO).suggestName("hdmi_out")
        hdmi := scanout.module.io.hdmiOut
        scanout.module.io.pxclk := pxClockNode.in.head._1.clock
        scanout.module.io.pxclkx5 := pxClockx5Node.in.head._1.clock
        scanout.module.io.videoReset := pxClockNode.in.head._1.reset.asBool || pxClockx5Node.in.head._1.reset.asBool
        hdmi
      }
      Some(hdmi)
    }
    case None => None
  }
}

class WithVideoScanout() extends Config((site, here, up) => {
  case VideoScanoutKey => {
    Some(VideoScanoutKeyParams())
  }
})
