package sifive.fpgashells.shell.xilinx

import chisel3._
import chisel3.experimental._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.prci._
import org.chipsalliance.cde.config._
import sifive.fpgashells.clocks._
import sifive.fpgashells.devices.xilinx.xilinxarty100tmig._
import sifive.fpgashells.ip.xilinx._
import sifive.fpgashells.shell._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.prci._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.util.ElaborationArtefacts


case class XilinxBoraLakeMIGParams(
  address : Seq[AddressSet]
)

class XilinxBoraLakeMIGPads(depth : BigInt) extends BoraLakeMIGIODDR(depth) {
  def this(c : XilinxBoraLakeMIGParams) {
    this(AddressRange.fromSets(c.address).head.size)
  }
}

class XilinxBoraLakeMIGIO(depth : BigInt) extends BoraLakeMIGIODDR(depth) with BoraLakeMIGIOClocksReset

class XilinxBoraLakeMIGIsland(c : XilinxBoraLakeMIGParams, val crossing: ClockCrossingType = AsynchronousCrossing(8))(implicit p: Parameters) extends LazyModule with CrossesToOnlyOneClockDomain {
  val ranges = AddressRange.fromSets(c.address)
  require (ranges.size == 1, "DDR range must be contiguous")
  val offset = ranges.head.base
  val depth = ranges.head.size
  require((depth<=0x100000000L),"BoraLakeMIG supports upto 4GB depth configuraton")
  
  val device = new MemoryDevice
  val node = AXI4SlaveNode(Seq(AXI4SlavePortParameters(
      slaves = Seq(AXI4SlaveParameters(
      address       = c.address,
      resources     = device.reg,
      regionType    = RegionType.UNCACHED,
      executable    = true,
      supportsWrite = TransferSizes(1, 64),
      supportsRead  = TransferSizes(1, 64))),
    beatBytes = 8)))

  lazy val module = new Impl
  class Impl extends LazyRawModuleImp(this) {
    val io = IO(new Bundle {
      val port = new XilinxBoraLakeMIGIO(depth)
    })
    override def provideImplicitClockToLazyChildren = true
    childClock := io.port.ui_clk
    childReset := io.port.ui_clk_sync_rst

    //MIG black box instantiation
    val blackbox = withClockAndReset(childClock, childReset) { Module(new BoraLakeMIG(depth)) }
    val (axi_async, _) = node.in(0)

    //pins to top level

    //inouts
    attach(io.port.ddr3_dq,blackbox.io.ddr3_dq)
    attach(io.port.ddr3_dqs_n,blackbox.io.ddr3_dqs_n)
    attach(io.port.ddr3_dqs_p,blackbox.io.ddr3_dqs_p)

    //outputs
    io.port.ddr3_addr         := blackbox.io.ddr3_addr
    io.port.ddr3_ba           := blackbox.io.ddr3_ba
    io.port.ddr3_ras_n        := blackbox.io.ddr3_ras_n
    io.port.ddr3_cas_n        := blackbox.io.ddr3_cas_n
    io.port.ddr3_we_n         := blackbox.io.ddr3_we_n
    io.port.ddr3_reset_n      := blackbox.io.ddr3_reset_n
    io.port.ddr3_ck_p         := blackbox.io.ddr3_ck_p
    io.port.ddr3_ck_n         := blackbox.io.ddr3_ck_n
    io.port.ddr3_cke          := blackbox.io.ddr3_cke
    io.port.ddr3_cs_n         := blackbox.io.ddr3_cs_n
    io.port.ddr3_dm           := blackbox.io.ddr3_dm
    io.port.ddr3_odt          := blackbox.io.ddr3_odt

    //inputs
    //NO_BUFFER clock
    blackbox.io.sys_clk_i     := io.port.sys_clk_i
    blackbox.io.clk_ref_i     := io.port.clk_ref_i

    io.port.ui_clk            := blackbox.io.ui_clk
    io.port.ui_clk_sync_rst   := blackbox.io.ui_clk_sync_rst
    io.port.mmcm_locked       := blackbox.io.mmcm_locked
    blackbox.io.aresetn       := io.port.aresetn
    blackbox.io.app_sr_req    := false.B
    blackbox.io.app_ref_req   := false.B
    blackbox.io.app_zq_req    := false.B
    //app_sr_active           := unconnected
    //app_ref_ack             := unconnected
    //app_zq_ack              := unconnected

    val awaddr = axi_async.aw.bits.addr - offset.U
    val araddr = axi_async.ar.bits.addr - offset.U

    //slave AXI interface write address ports
    blackbox.io.s_axi_awid    := axi_async.aw.bits.id
    blackbox.io.s_axi_awaddr  := awaddr //truncated
    blackbox.io.s_axi_awlen   := axi_async.aw.bits.len
    blackbox.io.s_axi_awsize  := axi_async.aw.bits.size
    blackbox.io.s_axi_awburst := axi_async.aw.bits.burst
    blackbox.io.s_axi_awlock  := axi_async.aw.bits.lock
    blackbox.io.s_axi_awcache := "b0011".U
    blackbox.io.s_axi_awprot  := axi_async.aw.bits.prot
    blackbox.io.s_axi_awqos   := axi_async.aw.bits.qos
    blackbox.io.s_axi_awvalid := axi_async.aw.valid
    axi_async.aw.ready        := blackbox.io.s_axi_awready

    //slave interface write data ports
    blackbox.io.s_axi_wdata   := axi_async.w.bits.data
    blackbox.io.s_axi_wstrb   := axi_async.w.bits.strb
    blackbox.io.s_axi_wlast   := axi_async.w.bits.last
    blackbox.io.s_axi_wvalid  := axi_async.w.valid
    axi_async.w.ready         := blackbox.io.s_axi_wready

    //slave interface write response
    blackbox.io.s_axi_bready  := axi_async.b.ready
    axi_async.b.bits.id       := blackbox.io.s_axi_bid
    axi_async.b.bits.resp     := blackbox.io.s_axi_bresp
    axi_async.b.valid         := blackbox.io.s_axi_bvalid

    //slave AXI interface read address ports
    blackbox.io.s_axi_arid    := axi_async.ar.bits.id
    blackbox.io.s_axi_araddr  := araddr // truncated
    blackbox.io.s_axi_arlen   := axi_async.ar.bits.len
    blackbox.io.s_axi_arsize  := axi_async.ar.bits.size
    blackbox.io.s_axi_arburst := axi_async.ar.bits.burst
    blackbox.io.s_axi_arlock  := axi_async.ar.bits.lock
    blackbox.io.s_axi_arcache := "b0011".U
    blackbox.io.s_axi_arprot  := axi_async.ar.bits.prot
    blackbox.io.s_axi_arqos   := axi_async.ar.bits.qos
    blackbox.io.s_axi_arvalid := axi_async.ar.valid
    axi_async.ar.ready        := blackbox.io.s_axi_arready

    //slace AXI interface read data ports
    blackbox.io.s_axi_rready  := axi_async.r.ready
    axi_async.r.bits.id       := blackbox.io.s_axi_rid
    axi_async.r.bits.data     := blackbox.io.s_axi_rdata
    axi_async.r.bits.resp     := blackbox.io.s_axi_rresp
    axi_async.r.bits.last     := blackbox.io.s_axi_rlast
    axi_async.r.valid         := blackbox.io.s_axi_rvalid

    //misc
    io.port.init_calib_complete := blackbox.io.init_calib_complete
    blackbox.io.sys_rst       := io.port.sys_rst
    //mig.device_temp         :- unconnceted
  }
}

class XilinxBoraLakeMIG(c : XilinxBoraLakeMIGParams, crossing: ClockCrossingType = AsynchronousCrossing(8))(implicit p: Parameters) extends LazyModule {
  val ranges = AddressRange.fromSets(c.address)
  val depth = ranges.head.size

  val buffer  = LazyModule(new TLBuffer)
  val toaxi4  = LazyModule(new TLToAXI4(adapterName = Some("mem")))
  val indexer = LazyModule(new AXI4IdIndexer(idBits = 4))
  val deint   = LazyModule(new AXI4Deinterleaver(p(CacheBlockBytes)))
  val yank    = LazyModule(new AXI4UserYanker)
  val island  = LazyModule(new XilinxBoraLakeMIGIsland(c, crossing))

  val node: TLInwardNode =
    island.crossAXI4In(island.node) := yank.node := deint.node := indexer.node := toaxi4.node := buffer.node

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val io = IO(new Bundle {
      val port = new XilinxBoraLakeMIGIO(depth)
    })

    io.port <> island.module.io.port
  }
}



// Black Box
class BoraLakeMIGIODDR(depth : BigInt) extends Bundle {
  require((depth<=0x100000000L),"BoraLakeMIGIODDR supports upto 4GB depth configuraton")
  val ddr3_addr             = Output(Bits(16.W))
  val ddr3_ba               = Output(Bits(3.W))
  val ddr3_ras_n            = Output(Bool())
  val ddr3_cas_n            = Output(Bool())
  val ddr3_we_n             = Output(Bool())
  val ddr3_reset_n          = Output(Bool())
  val ddr3_ck_p             = Output(Bits(1.W))
  val ddr3_ck_n             = Output(Bits(1.W))
  val ddr3_cke              = Output(Bits(1.W))
  val ddr3_cs_n             = Output(Bits(1.W))
  val ddr3_dm               = Output(Bits(8.W))
  val ddr3_odt              = Output(Bits(1.W))

  val ddr3_dq               = Analog(64.W)
  val ddr3_dqs_n            = Analog(8.W)
  val ddr3_dqs_p            = Analog(8.W)
}

trait BoraLakeMIGIOClocksReset extends Bundle {
  //inputs
  //"NO_BUFFER" clock source (must be connected to IBUF outside of IP)
  val sys_clk_i             = Input(Bool())
  val clk_ref_i             = Input(Bool())
  //user interface signals
  val ui_clk                = Output(Clock())
  val ui_clk_sync_rst       = Output(Bool())
  val mmcm_locked           = Output(Bool())
  val aresetn               = Input(Bool())
  //misc
  val init_calib_complete   = Output(Bool())
  val sys_rst               = Input(Bool())
}

//scalastyle:off
//turn off linter: blackbox name must match verilog module
class BoraLakeMIG(depth : BigInt)(implicit val p:Parameters) extends BlackBox
{
  require((depth<=0x100000000L),"BoraLakeMIG supports upto 4GB depth configuraton")

  val io = IO(new BoraLakeMIGIODDR(depth) with BoraLakeMIGIOClocksReset {
    // User interface signals
    val app_sr_req            = Input(Bool())
    val app_ref_req           = Input(Bool())
    val app_zq_req            = Input(Bool())
    val app_sr_active         = Output(Bool())
    val app_ref_ack           = Output(Bool())
    val app_zq_ack            = Output(Bool())
    //axi_s
    //slave interface write address ports
    val s_axi_awid            = Input(Bits(4.W))
    val s_axi_awaddr          = Input(Bits(if(depth<=0x40000000) 30.W else 32.W))
    val s_axi_awlen           = Input(Bits(8.W))
    val s_axi_awsize          = Input(Bits(3.W))
    val s_axi_awburst         = Input(Bits(2.W))
    val s_axi_awlock          = Input(Bits(1.W))
    val s_axi_awcache         = Input(Bits(4.W))
    val s_axi_awprot          = Input(Bits(3.W))
    val s_axi_awqos           = Input(Bits(4.W))
    val s_axi_awvalid         = Input(Bool())
    val s_axi_awready         = Output(Bool())
    //slave interface write data ports
    val s_axi_wdata           = Input(Bits(64.W))
    val s_axi_wstrb           = Input(Bits(8.W))
    val s_axi_wlast           = Input(Bool())
    val s_axi_wvalid          = Input(Bool())
    val s_axi_wready          = Output(Bool())
    //slave interface write response ports
    val s_axi_bready          = Input(Bool())
    val s_axi_bid             = Output(Bits(4.W))
    val s_axi_bresp           = Output(Bits(2.W))
    val s_axi_bvalid          = Output(Bool())
    //slave interface read address ports
    val s_axi_arid            = Input(Bits(4.W))
    val s_axi_araddr          = Input(Bits(if(depth<=0x40000000) 30.W else 32.W))
    val s_axi_arlen           = Input(Bits(8.W))
    val s_axi_arsize          = Input(Bits(3.W))
    val s_axi_arburst         = Input(Bits(2.W))
    val s_axi_arlock          = Input(Bits(1.W))
    val s_axi_arcache         = Input(Bits(4.W))
    val s_axi_arprot          = Input(Bits(3.W))
    val s_axi_arqos           = Input(Bits(4.W))
    val s_axi_arvalid         = Input(Bool())
    val s_axi_arready         = Output(Bool())
    //slave interface read data ports
    val s_axi_rready          = Input(Bool())
    val s_axi_rid             = Output(Bits(4.W))
    val s_axi_rdata           = Output(Bits(64.W))
    val s_axi_rresp           = Output(Bits(2.W))
    val s_axi_rlast           = Output(Bool())
    val s_axi_rvalid          = Output(Bool())
    //misc
    val device_temp           = Output(Bits(12.W))
  })


  val migprj = """{<?xml version="1.0" encoding="UTF-8"?>
    <Project NoOfControllers="1">
    <!-- IMPORTANT: This is an internal file that has been generated by the MIG software. Any direct editing or changes made to this file may result in unpredictable behavior or data corruption. It is strongly advised that users do not edit the contents of this file. Re-run the MIG GUI with the required settings if any of the options provided below need to be altered. -->
    <ModuleName>BoraLakeMIG</ModuleName>
    <dci_inouts_inputs>1</dci_inouts_inputs>
    <dci_inputs>1</dci_inputs>
    <Debug_En>OFF</Debug_En>
    <DataDepth_En>1024</DataDepth_En>
    <LowPower_En>ON</LowPower_En>
    <XADC_En>Enabled</XADC_En>
    <TargetFPGA>xc7k160ti-ffg676/-2L</TargetFPGA>
    <Version>4.2</Version>
    <SystemClock>No Buffer</SystemClock>
    <ReferenceClock>No Buffer</ReferenceClock>
    <SysResetPolarity>ACTIVE HIGH</SysResetPolarity>
    <BankSelectionFlag>FALSE</BankSelectionFlag>
    <InternalVref>1</InternalVref>
    <dci_hr_inouts_inputs>50 Ohms</dci_hr_inouts_inputs>
    <dci_cascade>0</dci_cascade>
    <FPGADevice>
        <selected>7k/xc7k160t-ffg676</selected>
    </FPGADevice>
    <Controller number="0">
        <MemoryDevice>DDR3_SDRAM/SODIMMs/MT8KTF51264HZ-1G6</MemoryDevice>
        <TimePeriod>3000</TimePeriod>
        <VccAuxIO>1.8V</VccAuxIO>
        <PHYRatio>4:1</PHYRatio>
        <InputClkFreq>166.666</InputClkFreq>
        <UIExtraClocks>0</UIExtraClocks>
        <MMCM_VCO>666</MMCM_VCO>
        <MMCMClkOut0> 1.000</MMCMClkOut0>
        <MMCMClkOut1>1</MMCMClkOut1>
        <MMCMClkOut2>1</MMCMClkOut2>
        <MMCMClkOut3>1</MMCMClkOut3>
        <MMCMClkOut4>1</MMCMClkOut4>
        <DataWidth>64</DataWidth>
        <DeepMemory>1</DeepMemory>
        <DataMask>1</DataMask>
        <ECC>Disabled</ECC>
        <Ordering>Normal</Ordering>
        <BankMachineCnt>4</BankMachineCnt>
        <CustomPart>FALSE</CustomPart>
        <NewPartName></NewPartName>
        <RowAddress>16</RowAddress>
        <ColAddress>10</ColAddress>
        <BankAddress>3</BankAddress>
        <MemoryVoltage>1.35V</MemoryVoltage>
        <UserMemoryAddressMap>BANK_ROW_COLUMN</UserMemoryAddressMap>
        <PinSelection>
            <Pin IN_TERM="" IOSTANDARD="SSTL135" PADName="AF7" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_addr[0]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135" PADName="Y11" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_addr[10]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135" PADName="AB12" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_addr[11]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135" PADName="Y13" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_addr[12]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135" PADName="Y8" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_addr[13]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135" PADName="AC12" SLEW="" VCCAUX_IO="HIGH" name="ddr3_addr[14]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135" PADName="AC13" SLEW="" VCCAUX_IO="HIGH" name="ddr3_addr[15]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135" PADName="AA9" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_addr[1]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135" PADName="AE7" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_addr[2]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135" PADName="AB9" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_addr[3]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135" PADName="AD8" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_addr[4]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135" PADName="AB10" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_addr[5]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135" PADName="AD9" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_addr[6]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135" PADName="AC9" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_addr[7]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135" PADName="AA12" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_addr[8]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135" PADName="AA13" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_addr[9]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135" PADName="W10" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_ba[0]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135" PADName="AC7" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_ba[1]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135" PADName="W9" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_ba[2]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135" PADName="AA10" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_cas_n"/>
            <Pin IN_TERM="" IOSTANDARD="DIFF_SSTL135" PADName="W11" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_ck_n[0]"/>
            <Pin IN_TERM="" IOSTANDARD="DIFF_SSTL135" PADName="V11" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_ck_p[0]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135" PADName="V9" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_cke[0]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135" PADName="AA8" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_cs_n[0]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135" PADName="W16" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dm[0]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135" PADName="AD19" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dm[1]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135" PADName="AE15" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dm[2]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135" PADName="AB15" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dm[3]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135" PADName="AF3" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dm[4]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135" PADName="AD6" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dm[5]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135" PADName="W1" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dm[6]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135" PADName="W3" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dm[7]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="V19" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[0]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="AC18" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[10]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="AD18" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[11]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="AA20" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[12]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="AA19" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[13]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="AC17" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[14]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="AB17" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[15]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="AF20" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[16]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="AF19" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[17]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="AE17" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[18]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="AF17" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[19]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="V16" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[1]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="AD16" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[20]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="AF15" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[21]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="AF14" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[22]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="AD15" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[23]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="AB16" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[24]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="AA15" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[25]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="AA14" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[26]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="AB14" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[27]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="AA18" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[28]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="AA17" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[29]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="Y17" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[2]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="AD14" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[30]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="AC14" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[31]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="AE5" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[32]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="AE3" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[33]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="AD1" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[34]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="AE1" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[35]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="AD4" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[36]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="AE6" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[37]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="AF2" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[38]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="AE2" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[39]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="V14" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[3]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="AB6" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[40]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="AA4" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[41]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="AC3" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[42]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="AC4" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[43]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="Y6" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[44]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="Y5" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[45]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="AC6" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[46]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="AB4" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[47]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="AC2" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[48]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="AB2" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[49]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="V17" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[4]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="Y2" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[50]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="Y1" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[51]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="AA3" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[52]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="Y3" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[53]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="V1" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[54]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="V2" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[55]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="U5" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[56]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="V6" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[57]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="V3" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[58]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="U1" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[59]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="V18" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[5]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="U7" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[60]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="U6" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[61]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="U2" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[62]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="V4" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[63]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="W15" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[6]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="W14" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[7]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="AB19" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[8]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135_T_DCI" PADName="AC19" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dq[9]"/>
            <Pin IN_TERM="" IOSTANDARD="DIFF_SSTL135_T_DCI" PADName="W19" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dqs_n[0]"/>
            <Pin IN_TERM="" IOSTANDARD="DIFF_SSTL135_T_DCI" PADName="AE20" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dqs_n[1]"/>
            <Pin IN_TERM="" IOSTANDARD="DIFF_SSTL135_T_DCI" PADName="AF18" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dqs_n[2]"/>
            <Pin IN_TERM="" IOSTANDARD="DIFF_SSTL135_T_DCI" PADName="Y16" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dqs_n[3]"/>
            <Pin IN_TERM="" IOSTANDARD="DIFF_SSTL135_T_DCI" PADName="AF4" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dqs_n[4]"/>
            <Pin IN_TERM="" IOSTANDARD="DIFF_SSTL135_T_DCI" PADName="AB5" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dqs_n[5]"/>
            <Pin IN_TERM="" IOSTANDARD="DIFF_SSTL135_T_DCI" PADName="AC1" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dqs_n[6]"/>
            <Pin IN_TERM="" IOSTANDARD="DIFF_SSTL135_T_DCI" PADName="W5" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dqs_n[7]"/>
            <Pin IN_TERM="" IOSTANDARD="DIFF_SSTL135_T_DCI" PADName="W18" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dqs_p[0]"/>
            <Pin IN_TERM="" IOSTANDARD="DIFF_SSTL135_T_DCI" PADName="AD20" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dqs_p[1]"/>
            <Pin IN_TERM="" IOSTANDARD="DIFF_SSTL135_T_DCI" PADName="AE18" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dqs_p[2]"/>
            <Pin IN_TERM="" IOSTANDARD="DIFF_SSTL135_T_DCI" PADName="Y15" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dqs_p[3]"/>
            <Pin IN_TERM="" IOSTANDARD="DIFF_SSTL135_T_DCI" PADName="AF5" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dqs_p[4]"/>
            <Pin IN_TERM="" IOSTANDARD="DIFF_SSTL135_T_DCI" PADName="AA5" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dqs_p[5]"/>
            <Pin IN_TERM="" IOSTANDARD="DIFF_SSTL135_T_DCI" PADName="AB1" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_dqs_p[6]"/>
            <Pin IN_TERM="" IOSTANDARD="DIFF_SSTL135_T_DCI" PADName="W6" SLEW="" VCCAUX_IO="HIGH" name="ddr3_dqs_p[7]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135" PADName="AA7" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_odt[0]"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135" PADName="AB7" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_ras_n"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135" PADName="AE13" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_reset_n"/>
            <Pin IN_TERM="" IOSTANDARD="SSTL135" PADName="Y10" SLEW="FAST" VCCAUX_IO="HIGH" name="ddr3_we_n"/>
        </PinSelection>
        <System_Control>
          <Pin Bank="Select Bank" PADName="No connect" name="sys_rst"/>
          <Pin Bank="Select Bank" PADName="No connect" name="init_calib_complete"/>
          <Pin Bank="Select Bank" PADName="No connect" name="tg_compare_error"/>
        </System_Control>
        <TimingParameters>
          <Parameters twtr="7.5" trrd="6" trefi="7.8" tfaw="30" trtp="7.5" tcke="5" trfc="260" trp="13.75" tras="35" trcd="13.75" />
        </TimingParameters>
        <mrBurstLength name="Burst Length" >8 - Fixed</mrBurstLength>
        <mrBurstType name="Read Burst Type and Length" >Sequential</mrBurstType>
        <mrCasLatency name="CAS Latency" >5</mrCasLatency>
        <mrMode name="Mode" >Normal</mrMode>
        <mrDllReset name="DLL Reset" >No</mrDllReset>
        <mrPdMode name="DLL control for precharge PD" >Slow Exit</mrPdMode>
        <emrDllEnable name="DLL Enable" >Enable</emrDllEnable>
        <emrOutputDriveStrength name="Output Driver Impedance Control" >RZQ/7</emrOutputDriveStrength>
        <emrMirrorSelection name="Address Mirroring" >Disable</emrMirrorSelection>
        <emrCSSelection name="Controller Chip Select Pin" >Enable</emrCSSelection>
        <emrRTT name="RTT (nominal) - On Die Termination (ODT)" >RZQ/6</emrRTT>
        <emrPosted name="Additive Latency (AL)" >0</emrPosted>
        <emrOCD name="Write Leveling Enable" >Disabled</emrOCD>
        <emrDQS name="TDQS enable" >Enabled</emrDQS>
        <emrRDQS name="Qoff" >Output Buffer Enabled</emrRDQS>
        <mr2PartialArraySelfRefresh name="Partial-Array Self Refresh" >Full Array</mr2PartialArraySelfRefresh>
        <mr2CasWriteLatency name="CAS write latency" >5</mr2CasWriteLatency>
        <mr2AutoSelfRefresh name="Auto Self Refresh" >Enabled</mr2AutoSelfRefresh>
        <mr2SelfRefreshTempRange name="High Temparature Self Refresh Rate" >Normal</mr2SelfRefreshTempRange>
        <mr2RTTWR name="RTT_WR - Dynamic On Die Termination (ODT)" >Dynamic ODT off</mr2RTTWR>
        <PortInterface>AXI</PortInterface>
        <AXIParameters>
            <C0_C_RD_WR_ARB_ALGORITHM>RD_PRI_REG</C0_C_RD_WR_ARB_ALGORITHM>
            <C0_S_AXI_ADDR_WIDTH>30</C0_S_AXI_ADDR_WIDTH>
            <C0_S_AXI_DATA_WIDTH>64</C0_S_AXI_DATA_WIDTH>
            <C0_S_AXI_ID_WIDTH>4</C0_S_AXI_ID_WIDTH>
            <C0_S_AXI_SUPPORTS_NARROW_BURST>0</C0_S_AXI_SUPPORTS_NARROW_BURST>
        </AXIParameters>
    </Controller>


    </Project>}"""




  val migprjname = """{/BoraLakeMIG.prj}"""
  val modulename = """BoraLakeMIG"""

  ElaborationArtefacts.add(
    modulename++".vivado.tcl",
    """set migprj """++migprj++"""
   set migprjfile """++migprjname++"""
   set migprjfilepath $ipdir$migprjfile
   set fp [open $migprjfilepath w+]
   puts $fp $migprj
   close $fp
   create_ip -vendor xilinx.com -library ip -name mig_7series -module_name """ ++ modulename ++ """ -dir $ipdir -force
   set_property CONFIG.XML_INPUT_FILE $migprjfilepath [get_ips """ ++ modulename ++ """] """
  )


}