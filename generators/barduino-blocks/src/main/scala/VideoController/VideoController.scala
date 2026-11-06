package barduino_blocks

import chisel3._
import chisel3.util._
import chisel3.experimental.{IntParam, BaseModule}
import freechips.rocketchip.prci._
import freechips.rocketchip.subsystem.{BaseSubsystem, PBUS, SBUS}
import org.chipsalliance.cde.config.{Parameters, Field, Config}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.regmapper.{HasRegMap, RegField, RegWriteFn}
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
  val epoch = Bool()
}

class VideoScanout(params: VideoScanoutKeyParams, beatBytes: Int)(implicit p: Parameters)
    extends ClockSinkDomain(ClockSinkParameters())(p) {
  val device = new SimpleDevice("video_scanout", Seq("barduino,video_scanout"))
  val node = TLRegisterNode(Seq(AddressSet(params.address, 4095)), device, "reg/control", concurrency=1, beatBytes=beatBytes)
  val dma_node = TLClientNode(Seq(TLMasterPortParameters.v1(
    clients=Seq(TLMasterParameters.v1(name="video-scanout", sourceId=IdRange(0, 8))), minLatency=1)))

  override lazy val module = new VideoScanoutImpl
  class VideoScanoutImpl extends Impl {
    val io = IO(new VideoScanoutIO)
    withClockAndReset(clock, reset) {
    require(beatBytes == 8, "Scanout requires a 64-bit bus")
    // Software may change base only with enable=0 and busy=0. Base is 64-byte aligned.
    val enabled = RegInit(false.B)
    val base = RegInit(0x81000000L.U(64.W))
    // Snapshot one pending flip; software must not modify its old buffer until ack.
    val nextBase = RegInit(0x81100000L.U(64.W))
    val pendingBase = Reg(UInt(64.W))
    val frameBase = RegInit(0x81000000L.U(64.W))
    val displayedBase = RegInit(0x81000000L.U(64.W))
    val flipPending = RegInit(false.B)
    val flipBusy = RegInit(false.B)
    val flipRejected = RegInit(false.B)
    val flipEpoch = RegInit(false.B)
    val selectedEpoch = RegInit(false.B)
    val requestEpoch = Reg(Bool())
    val flipsCompleted = RegInit(0.U(32.W))
    // Fixed eight-slot reorder storage for every runtime limit (1/2/4/8).
    // A source ID remains allocated until its complete burst is retired in order.
    val limit = RegInit(4.U(4.W))
    val allocated = RegInit(VecInit(Seq.fill(8)(false.B)))
    val complete = RegInit(VecInit(Seq.fill(8)(false.B)))
    val received = RegInit(VecInit(Seq.fill(8)(0.U(3.W))))
    val slotWord = Reg(Vec(8, UInt(17.W)))
    val slotFrame = Reg(Vec(8, UInt(8.W)))
    val slotEpoch = Reg(Vec(8, Bool()))
    // Registered dual-port storage infers block RAM instead of a 4096-FF mux bank.
    val data = SyncReadMem(64, UInt(64.W))
    val issueSlot = RegInit(0.U(3.W))
    val retireSlot = RegInit(0.U(3.W))
    val retireBeat = RegInit(0.U(3.W))
    val burstWord = RegInit(0.U(17.W))
    val dmaFrame = RegInit(0.U(8.W))
    val requestValid = RegInit(false.B)
    val requestAddress = Reg(UInt(64.W))
    val busy = requestValid || allocated.asUInt.orR
    val state = WireInit(Mux(busy, 2.U(2.W), 0.U(2.W))) // diagnostic/test compatibility
    dontTouch(state)
    val dmaError = RegInit(false.B)
    val runReset = reset.asBool || io.videoReset || !enabled
    val sourceReset = freechips.rocketchip.util.ResetCatchAndSync(clock, runReset)
    val pixelReset = freechips.rocketchip.util.ResetCatchAndSync(io.pxclk, runReset)
    val fifo = Module(new AsyncQueue(new ScanoutWord, AsyncQueueParams(depth=64)))
    fifo.io.enq_clock := clock
    fifo.io.enq_reset := sourceReset
    fifo.io.deq_clock := io.pxclk
    fifo.io.deq_reset := pixelReset

    val timingReset = freechips.rocketchip.util.ResetCatchAndSync(io.pxclk, reset.asBool || io.videoReset)
    val streamRunning = Wire(Bool())
    val pixelDisplayedEpoch = Wire(Bool())
    val pixelUnderflowEver = Wire(Bool())
    val pixelVsync = Wire(Bool())
    val pixelUnderflow = Wire(Bool())
    withClockAndReset(io.pxclk, timingReset) {
      val h = RegInit(0.U(10.W))
      val v = RegInit(0.U(10.W))
      val frame = withReset(pixelReset) { RegInit(0.U(8.W)) }
      val word = withReset(pixelReset) { RegInit(0.U(17.W)) }
      val lane = withReset(pixelReset) { RegInit(0.U(2.W)) }
      val running = withReset(pixelReset) { RegInit(false.B) }
      val displayedEpoch = withReset(pixelReset) { RegInit(false.B) }
      val underflow = withReset(pixelReset) { RegInit(false.B) }
      val underflowEver = RegInit(false.B)
      streamRunning := running
      pixelDisplayedEpoch := displayedEpoch
      pixelUnderflowEver := underflowEver
      val active = h >= 144.U && h < 784.U && v >= 35.U && v < 515.U
      val vsync = v < 2.U
      pixelVsync := RegNext(vsync, false.B)
      pixelUnderflow := underflow
      when(h === 799.U) {
        h := 0.U
        when(v === 524.U) {
          v := 0.U
          when(!pixelReset) {
            running := true.B
            when(running) { frame := frame + 1.U }
            word := 0.U; lane := 0.U
          }
        }
          .otherwise { v := v + 1.U }
      }.otherwise { h := h + 1.U }
      val tag = fifo.io.deq.bits
      val matching = tag.frame === frame && tag.word === word
      // Drop late data at up to one word/pixel clock. Hold future-frame data.
      val frameDelta = tag.frame - frame
      val stale = frameDelta(7) || (tag.frame === frame && tag.word < word)
      fifo.io.deq.ready := running && (stale || (active && lane === 3.U && matching))
      val validPixel = active && running && fifo.io.deq.valid && matching
      when(vsync) { underflow := false.B }
      when(active && running) {
        lane := lane + 1.U
        when(lane === 3.U) { word := word + 1.U }
        when(!validPixel && !pixelReset) { underflow := true.B; underflowEver := true.B }
        // The epoch can change only at the first visible pixel of a frame.
        when(validPixel && word === 0.U && lane === 0.U) { displayedEpoch := tag.epoch }
      }
      val pixel = (tag.data >> (lane << 4))(15, 0)
      val tx = Module(new hdmi_tx_v1_0)
      tx.io.pix_clk := io.pxclk
      tx.io.pix_clkx5 := io.pxclkx5
      tx.io.rst := timingReset
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
    val displayedEpochSync = statusSync(pixelDisplayedEpoch)
    val underflowEverStatus = statusSync(pixelUnderflowEver)
    when(enabled && flipBusy && !flipPending && displayedEpochSync === flipEpoch) {
      flipBusy := false.B
      flipsCompleted := flipsCompleted + 1.U
      displayedBase := pendingBase
    }
    val (tl, edge) = dma_node.out(0)
    val occupancy = PopCount(allocated)
    val admissionLimit = Mux(limit < 1.U, 1.U, Mux(limit > 8.U, 8.U, limit))
    when(enabled && !sourceReset && !requestValid &&
      !allocated(issueSlot) && occupancy < admissionLimit) {
      requestValid := true.B
      val switchFrame = burstWord === 0.U && flipPending
      requestAddress := Mux(switchFrame, pendingBase, frameBase) + (burstWord << 3)
      requestEpoch := Mux(switchFrame, flipEpoch, selectedEpoch)
      when(switchFrame) {
        frameBase := pendingBase
        selectedEpoch := flipEpoch
        flipPending := false.B
      }
    }
    // Once offered, an A request is never withdrawn, even on software disable.
    tl.a.valid := requestValid
    tl.a.bits := edge.Get(issueSlot, requestAddress, 6.U)._2
    when(tl.a.fire) {
      requestValid := false.B
      allocated(issueSlot) := true.B
      complete(issueSlot) := false.B
      received(issueSlot) := 0.U
      slotWord(issueSlot) := burstWord
      slotFrame(issueSlot) := dmaFrame
      slotEpoch(issueSlot) := requestEpoch
      issueSlot := issueSlot + 1.U
      when(burstWord === (76800-8).U) { burstWord := 0.U; dmaFrame := dmaFrame + 1.U }
        .otherwise { burstWord := burstWord + 8.U }
    }
    val responseSlot = tl.d.bits.source(2, 0)
    // Every accepted request owns space for all eight D beats: never block DDR
    // waiting for pixel consumption or an earlier source ID's response.
    tl.d.ready := true.B
    when(tl.d.fire) {
      assert(allocated(responseSlot), "Unexpected scanout response source")
      assert(!complete(responseSlot), "Extra scanout response beat")
      data.write(Cat(responseSlot, received(responseSlot)), tl.d.bits.data)
      received(responseSlot) := received(responseSlot) + 1.U
      when(received(responseSlot) === 7.U) { complete(responseSlot) := true.B }
      when(tl.d.bits.denied || tl.d.bits.corrupt) { dmaError := true.B }
    }
    val retireReady = allocated(retireSlot) && complete(retireSlot)
    val retireQ = withReset(sourceReset) { Module(new Queue(UInt(64.W), 2)) }
    val readInFlight = withReset(sourceReset) { RegInit(false.B) }
    val readBeat = withReset(sourceReset) { RegInit(0.U(3.W)) }
    val readsDone = withReset(sourceReset) { RegInit(false.B) }
    fifo.io.enq.valid := retireQ.io.deq.valid && enabled && !sourceReset
    fifo.io.enq.bits.data := retireQ.io.deq.bits
    fifo.io.enq.bits.word := slotWord(retireSlot) + retireBeat
    fifo.io.enq.bits.frame := slotFrame(retireSlot)
    fifo.io.enq.bits.epoch := slotEpoch(retireSlot)
    retireQ.io.deq.ready := fifo.io.enq.ready && enabled && !sourceReset
    // Reserve space for the memory response before each read. Include the
    // currently returning word and a same-cycle dequeue for full-rate streaming.
    val used = retireQ.io.count +& readInFlight.asUInt
    val issueRead = retireReady && enabled && !sourceReset && !readsDone &&
      used < (2.U +& fifo.io.enq.fire.asUInt)
    val readResult = data.read(Cat(retireSlot, readBeat), issueRead)
    readInFlight := issueRead
    retireQ.io.enq.valid := readInFlight && enabled && !sourceReset
    retireQ.io.enq.bits := readResult
    when(readInFlight && enabled && !sourceReset) {
      assert(retireQ.io.enq.ready, "Reorder RAM read response overflow")
    }
    when(issueRead) {
      readBeat := readBeat + 1.U
      when(readBeat === 7.U) { readsDone := true.B }
    }
    // Disabled scanout still drains every issued response, then discards it.
    val discard = retireReady && (!enabled || sourceReset)
    when(fifo.io.enq.fire || discard) {
      when(retireBeat === 7.U || discard) {
        allocated(retireSlot) := false.B
        complete(retireSlot) := false.B
        retireSlot := retireSlot + 1.U
        retireBeat := 0.U
        readBeat := 0.U; readsDone := false.B
      }.otherwise { retireBeat := retireBeat + 1.U }
    }
    when(!enabled && !busy) {
      burstWord := 0.U; dmaFrame := 0.U; dmaError := false.B
      issueSlot := 0.U; retireSlot := 0.U; retireBeat := 0.U
      frameBase := base; displayedBase := base
      flipPending := false.B; flipBusy := false.B
      flipEpoch := false.B; selectedEpoch := false.B
    }
    // 0x00/0x04 preserve the original status offsets; remaining registers are new.
    node.regmap(
      0x00 -> Seq(RegField.r(1, vsyncStatus)),
      0x04 -> Seq(RegField.r(1, underflowStatus)),
      0x08 -> Seq(RegField(1, enabled)),
      0x0c -> Seq(RegField.r(1, busy), RegField.r(1, videoReady), RegField.r(1, dmaError)),
      0x10 -> Seq(RegField(64, base)),
      0x18 -> Seq(RegField(4, limit)),
      0x20 -> Seq(RegField(64, nextBase)),
      0x28 -> Seq(RegField(1, flipBusy, RegWriteFn((valid: Bool, value: UInt) => {
        val ready = !flipBusy || !value(0)
        when(valid && ready && value(0)) {
          when(enabled && nextBase(5, 0) === 0.U) {
            pendingBase := nextBase
            flipEpoch := !flipEpoch
            flipPending := true.B; flipBusy := true.B; flipRejected := false.B
          }.otherwise { flipRejected := true.B }
        }
        ready
      })), RegField.r(1, flipPending), RegField.r(1, flipRejected)),
      0x30 -> Seq(RegField.r(32, flipsCompleted)),
      0x38 -> Seq(RegField.r(64, displayedBase)),
      0x40 -> Seq(RegField.r(1, underflowEverStatus)))
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
