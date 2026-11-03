`timescale 1ns/1ps
// Pixel-path test only: transmitter is stubbed; no claim about OSERDES/board behavior.
module hdmi_tx_v1_0(input pix_clk, pix_clkx5, rst, input [7:0] red,green,blue,
 input hsync,vsync,vde, input [3:0] aux0_din,aux1_din,aux2_din,input ade,
 output TMDS_CLK_P,TMDS_CLK_N,output [2:0] TMDS_DATA_P,TMDS_DATA_N);
 assign TMDS_CLK_P=pix_clk; assign TMDS_CLK_N=~pix_clk;
 assign TMDS_DATA_P={red[0],green[0],blue[0]}; assign TMDS_DATA_N=~TMDS_DATA_P;
endmodule
module tb;
 logic clk=0, px=0, fast=0, reset=1, videoReset=1;
 always #5 clk=~clk;
 always #19.858 px=~px;
 always #3.9716 fast=~fast;
 logic av=0, ar, dv, dr=1;
 logic [28:0] addr=0;
 logic [63:0] wdata=0, rdata;
 logic [2:0] opcode=0;
 logic dmaAV,dmaDR,dmaDV;
 logic [31:0] dmaAddr;
 logic [63:0] dmaData;
 logic pending=0, stall=0;
 logic [31:0] captured;
 integer beat=0, requests=0, checked=0, pattern=0;
 bit checking=0;
 function automatic logic [15:0] color(input integer phase, input integer pixel);
  if(phase==1) case(pixel%4)
   0: return 16'h001f; 1:return 16'h07e0; 2:return 16'hf800; default:return 16'hffff;
  endcase
  case((pixel%640)/80)
   0:return 16'hffff; 1:return 16'h07ff; 2:return 16'hffe0; 3:return 16'h07e0;
   4:return 16'hf81f; 5:return 16'h001f; 6:return 16'hf800; default:return 0;
  endcase
 endfunction
 always_comb begin
  dmaDV=pending && !stall;
  for(integer j=0;j<4;j++)
   dmaData[j*16+:16]=color(captured>=32'h81100000 ? 1:0, integer'((captured & 32'hfffff)/2)+beat*4+j);
 end
 always @(posedge clk) begin
  if(reset) begin pending<=0; beat<=0; end
  else begin
   if(dmaAV && !pending) begin
    if(dmaAddr[5:0]!=0) $fatal(1,"unaligned DMA");
    if(!((dmaAddr>=32'h81000000 && dmaAddr<32'h81096000)||(dmaAddr>=32'h81100000 && dmaAddr<32'h81196000)))
     $fatal(1,"DMA outside framebuffer: %h",dmaAddr);
    pending<=1; captured<=dmaAddr; beat<=0; requests<=requests+1;
   end
   if(dmaDV && dmaDR) begin
    if(beat==7) pending<=0;
    else beat<=beat+1;
   end
  end
 end
 ClockSinkDomain_2 dut(
  .auto_clock_in_clock(clk),.auto_clock_in_reset(reset),.io_pxclk(px),.io_pxclkx5(fast),.io_videoReset(videoReset),
  .auto_dma_out_a_ready(!pending),.auto_dma_out_a_valid(dmaAV),.auto_dma_out_a_bits_address(dmaAddr),
  .auto_dma_out_d_ready(dmaDR),.auto_dma_out_d_valid(dmaDV),.auto_dma_out_d_bits_denied(1'b0),
  .auto_dma_out_d_bits_data(dmaData),.auto_dma_out_d_bits_corrupt(1'b0),
  .auto_in_a_ready(ar),.auto_in_a_valid(av),.auto_in_a_bits_opcode(opcode),.auto_in_a_bits_size(2'd3),
  .auto_in_a_bits_source(8'd0),.auto_in_a_bits_address(addr),.auto_in_a_bits_mask(8'hff),.auto_in_a_bits_data(wdata),
  .auto_in_d_ready(dr),.auto_in_d_valid(dv),.auto_in_d_bits_data(rdata),
  .auto_in_d_bits_opcode(),.auto_in_d_bits_size(),.auto_in_d_bits_source(),
  .io_hdmiOut_TMDS_CLK_P(),.io_hdmiOut_TMDS_CLK_N(),.io_hdmiOut_TMDS_DATA_P(),.io_hdmiOut_TMDS_DATA_N());
 task automatic writeReg(input logic [28:0] a, input logic [63:0] d);
  @(negedge clk);addr=a;wdata=d;opcode=0;av=1;
  do @(posedge clk); while(!ar);
  @(negedge clk);av=0;
  repeat(8) @(posedge clk);
 endtask
 always @(posedge px) begin : pixels
  logic [15:0] expected;
  logic [23:0] rgb;
  if(checking && dut.tx.vde && !dut.tx.rst) begin
   expected=color(pattern,integer'(dut.h)-144);
   rgb={{expected[4:0],expected[4:2]},{expected[10:5],expected[10:9]},{expected[15:11],expected[15:13]}};
   if({dut.tx.red,dut.tx.green,dut.tx.blue} !== rgb)
    $fatal(1,"pixel mismatch h=%0d v=%0d frame=%0d word=%0d expected=%h got=%h",dut.h,dut.v,dut.frame,dut.word,rgb,{dut.tx.red,dut.tx.green,dut.tx.blue});
   checked++;
  end
 end
 initial begin
  #1000;reset=0;videoReset=0;
  repeat(100) @(posedge clk);
  if(requests!=0) $fatal(1,"DMA started disabled");
  writeReg(29'h10081010,64'h81000000);
  writeReg(29'h10081008,1); checking=1;
  wait(dut.frame==1); checking=0;
  if(checked!=307200) $fatal(1,"wrong frame pixel count %0d",checked);
  wait(pending && beat==2); // disable with an outstanding response
  writeReg(29'h10081008,0);
  wait(!pending && dut.state==0);
  repeat(100) @(posedge clk);
  writeReg(29'h10081010,64'h81100000); pattern=1;checked=0;
  writeReg(29'h10081008,1);checking=1;
  wait(dut.frame==1); checking=0;
  if(checked!=307200) $fatal(1,"wrong second frame count");
  // Force underflow, restore responses, and check a complete recovered frame.
  wait(dut.v==50); @(negedge clk);stall=1;
  repeat(4000) @(posedge px);
  if(!dut.underflow) $fatal(1,"underflow not detected");
  @(negedge clk);stall=0;
  wait(dut.frame==2);checked=0;checking=1;
  wait(dut.frame==3);checking=0;
  if(checked!=307200) $fatal(1,"wrong recovered frame count");
  // Harness asserts system reset as well as video reset on video PLL lock loss.
  @(negedge clk);reset=1;videoReset=1;
  repeat(100) @(posedge clk);
  @(negedge clk);reset=0;videoReset=0;
  repeat(100) @(posedge clk);
  if(dmaAV || pending) $fatal(1,"DMA restarted without software enable after reset");
  pattern=0;checked=0;
  writeReg(29'h10081010,64'h81000000);
  writeReg(29'h10081008,1);checking=1;
  wait(dut.frame==1);checking=0;
  if(checked!=307200) $fatal(1,"wrong post-reset frame count");
  $display("PASS: bars, pixel ordering, frame wrap, stop/drain/base switch, underflow recovery, PLL/system reset restart; requests=%0d",requests);
  $finish;
 end
 initial begin #200000000; $fatal(1,"test timed out"); end
endmodule
