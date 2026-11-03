package sifive.fpgashells.shell.xilinx

import chisel3._
import chisel3.experimental.dataview._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.prci._
import org.chipsalliance.cde.config._
import sifive.fpgashells.clocks._
import sifive.fpgashells.ip.xilinx._
import sifive.fpgashells.shell._
import freechips.rocketchip.util.ElaborationArtefacts

class SysClockBoraLakePlacedOverlay(val shell: BoraLakeShellBasicOverlays, name: String, val designInput: ClockInputDesignInput, val shellInput: ClockInputShellInput)
  extends SingleEndedClockInputXilinxPlacedOverlay(name, designInput, shellInput)
{
  val node = shell { ClockSourceNode(freqMHz = 50, jitterPS = 50) }

  shell { InModuleBody {
    val clk: Clock = io
    shell.xdc.addPackagePin(clk, "F22")
    shell.xdc.addIOStandard(clk, "LVCMOS33")
  } }
}
class SysClockBoraLakeShellPlacer(val shell: BoraLakeShellBasicOverlays, val shellInput: ClockInputShellInput)(implicit val valName: ValName)
  extends ClockInputShellPlacer[BoraLakeShellBasicOverlays] {
  def place(designInput: ClockInputDesignInput) = new SysClockBoraLakePlacedOverlay(shell, valName.name, designInput, shellInput)
}



//LEDS - r0, g0, b0, 2 normal leds
object LEDBoraLakePinConstraints{
  val pins = Seq("D24", "D23", "F23", "E22", "G22", "E21")
}
class LEDBoraLakePlacedOverlay(val shell: BoraLakeShellBasicOverlays, name: String, val designInput: LEDDesignInput, val shellInput: LEDShellInput)
  extends LEDXilinxPlacedOverlay(name, designInput, shellInput, packagePin = Some(LEDBoraLakePinConstraints.pins(shellInput.number)))
class LEDBoraLakeShellPlacer(val shell: BoraLakeShellBasicOverlays, val shellInput: LEDShellInput)(implicit val valName: ValName)
  extends LEDShellPlacer[BoraLakeShellBasicOverlays] {
  def place(designInput: LEDDesignInput) = new LEDBoraLakePlacedOverlay(shell, valName.name, designInput, shellInput)
}



//Buttons
object ButtonBoraLakePinConstraints {
  val pins = Seq("G24", "F24", "E25", "D25", "G25")
}
class ButtonBoraLakePlacedOverlay(val shell: BoraLakeShellBasicOverlays, name: String, val designInput: ButtonDesignInput, val shellInput: ButtonShellInput)
  extends ButtonXilinxPlacedOverlay(name, designInput, shellInput, packagePin = Some(ButtonBoraLakePinConstraints.pins(shellInput.number)))
class ButtonBoraLakeShellPlacer(val shell: BoraLakeShellBasicOverlays, val shellInput: ButtonShellInput)(implicit val valName: ValName)
  extends ButtonShellPlacer[BoraLakeShellBasicOverlays] {
  def place(designInput: ButtonDesignInput) = new ButtonBoraLakePlacedOverlay(shell, valName.name, designInput, shellInput)
}



//Core to shell external resets
class CTSResetBoraLakePlacedOverlay(val shell: BoraLakeShellBasicOverlays, name: String, val designInput: CTSResetDesignInput, val shellInput: CTSResetShellInput)
  extends CTSResetPlacedOverlay(name, designInput, shellInput)
class CTSResetBoraLakeShellPlacer(val shell: BoraLakeShellBasicOverlays, val shellInput: CTSResetShellInput)(implicit val valName: ValName)
  extends CTSResetShellPlacer[BoraLakeShellBasicOverlays] {
  def place(designInput: CTSResetDesignInput) = new CTSResetBoraLakePlacedOverlay(shell, valName.name, designInput, shellInput)
}



case object BoraLakeDDRSize extends Field[BigInt](0x40000000L) // expose 1 GiB; keep the existing 30-bit MIG interface
class DDRBoraLakePlacedOverlay(val shell: BoraLakeShellBasicOverlays, name: String, val designInput: DDRDesignInput, val shellInput: DDRShellInput)
  extends DDRPlacedOverlay[XilinxBoraLakeMIGPads](name, designInput, shellInput)
{
  val size = p(BoraLakeDDRSize)

  val ddrClk1 = shell { ClockSinkNode(freqMHz = 166.666)}
  val ddrClk2 = shell { ClockSinkNode(freqMHz = 200)}
  val ddrGroup = shell { ClockGroup() }
  ddrClk1 := di.wrangler := ddrGroup := di.corePLL
  ddrClk2 := di.wrangler := ddrGroup
  
  val migParams = XilinxBoraLakeMIGParams(address = AddressSet.misaligned(di.baseAddress, size))
  val mig = LazyModule(new XilinxBoraLakeMIG(migParams))
  val ddrUI     = shell { ClockSourceNode(freqMHz = 1000.0 / 12.0) }
  val areset    = shell { ClockSinkNode(Seq(ClockSinkParameters())) }
  areset := di.wrangler := ddrUI

  def overlayOutput = DDROverlayOutput(ddr = mig.node)
  def ioFactory = new XilinxBoraLakeMIGPads(size)

  shell { InModuleBody {
    require (shell.sys_clock.get.isDefined, "Use of DDRBoraLakePlacedOverlay depends on SysClockBoraLakePlacedOverlay")
    val (sys, _) = shell.sys_clock.get.get.overlayOutput.node.out(0)
    val (ui, _) = ddrUI.out(0)
    val (dclk1, _) = ddrClk1.in(0)
    val (dclk2, _) = ddrClk2.in(0)
    val (ar, _) = areset.in(0)
    val port = mig.module.io.port
    
    io <> port.viewAsSupertype(new XilinxBoraLakeMIGPads(mig.depth))
    ui.clock := port.ui_clk
    // ui.reset := !port.mmcm_locked || port.ui_clk_sync_rst
    ui.reset := !port.mmcm_locked || port.ui_clk_sync_rst
    port.sys_clk_i := dclk1.clock.asUInt
    port.clk_ref_i := dclk2.clock.asUInt
    port.sys_rst := shell.pllReset
    port.aresetn := !(ar.reset.asBool)
  } }

  shell.sdc.addGroup(clocks = Seq("clk_pll_i"), pins = Seq(mig.island.module.blackbox.io.ui_clk))
}
class DDRBoraLakeShellPlacer(val shell: BoraLakeShellBasicOverlays, val shellInput: DDRShellInput)(implicit val valName: ValName)
  extends DDRShellPlacer[BoraLakeShellBasicOverlays] {
  def place(designInput: DDRDesignInput) = new DDRBoraLakePlacedOverlay(shell, valName.name, designInput, shellInput)
}



abstract class BoraLakeShellBasicOverlays()(implicit p: Parameters) extends Series7Shell {
  // Order matters; ddr depends on sys_clock
  val sys_clock = Overlay(ClockInputOverlayKey, new SysClockBoraLakeShellPlacer(this, ClockInputShellInput()))
  val led       = Seq.tabulate(6)(i => Overlay(LEDOverlayKey, new LEDBoraLakeShellPlacer(this, LEDMetas(i))(valName = ValName(s"led_$i"))))
  val button    = Seq.tabulate(5)(i => Overlay(ButtonOverlayKey, new ButtonBoraLakeShellPlacer(this, ButtonShellInput(number = i))(valName = ValName(s"button_$i"))))
  val ddr       = Overlay(DDROverlayKey, new DDRBoraLakeShellPlacer(this, DDRShellInput()))
  val cts_reset = Overlay(CTSResetOverlayKey, new CTSResetBoraLakeShellPlacer(this, CTSResetShellInput()))

  def LEDMetas(i: Int): LEDShellInput =
    LEDShellInput(
      color = if((i < 12) && (i % 3 == 1)) "green" else if((i < 12) && (i % 3 == 2)) "blue" else "red",
      rgb = (i < 12),
      number = i)
}

class BoraLakeShell()(implicit p: Parameters) extends BoraLakeShellBasicOverlays
{
  val resetPin = InModuleBody { Wire(Bool()) }
  // PLL reset causes
  val pllReset = InModuleBody { Wire(Bool()) }

  val topDesign = LazyModule(p(DesignKey)(designParameters))

  // Place the sys_clock at the Shell if the user didn't ask for it
  p(ClockInputOverlayKey).foreach(_.place(ClockInputDesignInput()))
  override lazy val module = new Impl
  class Impl extends LazyRawModuleImp(this) {
    override def provideImplicitClockToLazyChildren = true

    val reset = IO(Input(Bool()))
    xdc.addPackagePin(reset, "C26")
    xdc.addIOStandard(reset, "LVCMOS33")




    val VREF0 = IO(Input(Bool()))
    xdc.addPackagePin(VREF0, "H11")
    xdc.addIOStandard(VREF0, "LVCMOS12")

    val VREF1 = IO(Input(Bool()))
    xdc.addPackagePin(VREF1, "C13")
    xdc.addIOStandard(VREF1, "LVCMOS12")

    val VREF2 = IO(Input(Bool()))
    xdc.addPackagePin(VREF2, "D16")
    xdc.addIOStandard(VREF2, "LVCMOS12")

    val VREF3 = IO(Input(Bool()))
    xdc.addPackagePin(VREF3, "J20")
    xdc.addIOStandard(VREF3, "LVCMOS12")




    val reset_ibuf = Module(new IBUF)
    reset_ibuf.io.I := ~reset
    val sysclk: Clock = sys_clock.get() match {
      case Some(x: SysClockBoraLakePlacedOverlay) => x.clock
    }
    val powerOnReset = PowerOnResetFPGAOnly(sysclk)
    sdc.addAsyncPath(Seq(powerOnReset))

    resetPin := reset_ibuf.io.O

    pllReset :=
      (~reset_ibuf.io.O) || powerOnReset //BoraLake is not active low reset
  }
}
