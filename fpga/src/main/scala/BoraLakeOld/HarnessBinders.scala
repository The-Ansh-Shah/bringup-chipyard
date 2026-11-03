package chipyard.fpga.boralakeold

import chisel3._

import freechips.rocketchip.jtag.{JTAGIO}
import freechips.rocketchip.subsystem.{PeripheryBusKey}
import freechips.rocketchip.tilelink.{TLBundle}
import freechips.rocketchip.diplomacy.{LazyRawModuleImp}
import org.chipsalliance.diplomacy.nodes.{HeterogeneousBag}
import sifive.blocks.devices.uart.{UARTPortIO, UARTParams}
import sifive.blocks.devices.jtag.{JTAGPins, JTAGPinsFromPort}
import sifive.blocks.devices.pinctrl.{BasePin}
import sifive.fpgashells.shell._
import sifive.fpgashells.ip.xilinx._
import sifive.fpgashells.shell.xilinx._
import sifive.fpgashells.clocks._
import chipyard._
import chipyard.harness._
import chipyard.iobinders._
import testchipip.serdes._
import testchipip.serdes.old._

class WithBoraLakeUARTTSI extends HarnessBinder({
  case (th: HasHarnessInstantiators, port: UARTTSIPort, chipId: Int) => {
    val ath = th.asInstanceOf[LazyRawModuleImp].wrapper.asInstanceOf[BoraLakeHarness]
    val harnessIO = IO(new UARTPortIO(port.io.uartParams)).suggestName("uart_tsi")
    harnessIO <> port.io.uart
    val packagePinsWithPackageIOs = Seq(
      ("D23" , IOPin(harnessIO.rxd)),
      ("D24", IOPin(harnessIO.txd)))

    packagePinsWithPackageIOs foreach { case (pin, io) => {
      ath.xdc.addPackagePin(io, pin)
      ath.xdc.addIOStandard(io, "LVCMOS33")
      // RX first-stage sampling is asynchronous; do not force its full fanout into IOBs.
      if (pin == "D24") ath.xdc.addIOB(io)
    } }
  }
})



class WithBoraLakeTDDRTL extends HarnessBinder({
  case (th: HasHarnessInstantiators, port: TLMemPort, chipId: Int) => {
    val ath = th.asInstanceOf[LazyRawModuleImp].wrapper.asInstanceOf[BoraLakeHarness]
    val bundles = ath.ddrClient.out.map(_._1)
    val ddrClientBundle = Wire(new HeterogeneousBag(bundles.map(_.cloneType)))
    bundles.zip(ddrClientBundle).foreach { case (bundle, io) => bundle <> io }
    ddrClientBundle <> port.io
  }
})

class WithBoraLakeHDMI extends HarnessBinder({
  case (th: HasHarnessInstantiators, port: HDMIOutPort, chipId: Int) => {
    val ath = th.asInstanceOf[LazyRawModuleImp].wrapper.asInstanceOf[BoraLakeHarness]
    val harnessIO = IO(new barduino_blocks.HDMIVideoIO).suggestName("hdmi")
    harnessIO <> port.io
    val packagePinsWithPackageIOs = Seq(
      ("V24" , IOPin(harnessIO.TMDS_CLK_N)),
      ("V23" , IOPin(harnessIO.TMDS_CLK_P)),
      ("W26" , IOPin(harnessIO.TMDS_DATA_N, 0)),
      ("V26" , IOPin(harnessIO.TMDS_DATA_N, 1)),
      ("U25" , IOPin(harnessIO.TMDS_DATA_N, 2)),
      ("W25" , IOPin(harnessIO.TMDS_DATA_P, 0)),
      ("U26" , IOPin(harnessIO.TMDS_DATA_P, 1)),
      ("U24" , IOPin(harnessIO.TMDS_DATA_P, 2)))

    packagePinsWithPackageIOs foreach { case (pin, io) => {
      ath.xdc.addPackagePin(io, pin)
      ath.xdc.addIOStandard(io, "TMDS_33")
    } }
    
    val hdmi_tx_hpd = IO(Output(Bool()))
    hdmi_tx_hpd := true.B
    ath.xdc.addPackagePin(hdmi_tx_hpd, "V22")
    ath.xdc.addIOStandard(hdmi_tx_hpd, "LVCMOS33")


  }
})
