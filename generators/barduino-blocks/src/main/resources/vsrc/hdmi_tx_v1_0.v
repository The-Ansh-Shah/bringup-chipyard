//////////////////////////////////////////////////////////////////////////////////
// Module: hdmi_tx_v1_0
// Author: Tinghui Wang
//
// Copyright @ 2017 RealDigital.org
//
// Description:
//   HDMI/DVI encoder module for Xilinx 7-series FPGA.
//
// Note:
//   Part of the codes used in this module originated from XAPP460 by Bob Feng.
//
// History:
//   11/12/17: Created
//
// License: BSD 3-Clause
//
// Redistribution and use in source and binary forms, with or without 
// modification, are permitted provided that the following conditions are met:
//
// 1. Redistributions of source code must retain the above copyright notice, this 
//    list of conditions and the following disclaimer.
//
// 2. Redistributions in binary form must reproduce the above copyright notice, 
//    this list of conditions and the following disclaimer in the documentation 
//    and/or other materials provided with the distribution.
//
// 3. Neither the name of the copyright holder nor the names of its contributors 
//    may be used to endorse or promote products derived from this software 
//    without specific prior written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
// ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED 
// WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE 
// DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE 
// FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL 
// DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR 
// SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER 
// CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, 
// OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE 
// OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
//
// Xilinx Disclaimer From XAPP460:
//   LIMITED WARRANTY AND DISCLAMER. These designs are provided to you "as is". 
//   Xilinx and its licensors makeand you receive no warranties or conditions, 
//   express, implied, statutory or otherwise, and Xilinx specifically disclaims 
//   any implied warranties of merchantability, non-infringement,or fitness for a 
//   particular purpose. Xilinx does notwarrant that the functions contained in 
//   these designs will meet your requirements, or that the operation of these 
//   designs will be uninterrupted or error free, or that defects in the Designs
//   will be corrected. Furthermore, Xilinx does not warrantor make any 
//   representations regarding use or the results ofthe use of the designs in 
//   terms of correctness, accuracy, reliability, or otherwise.
//
//   LIMITATION OF LIABILITY. In no event will Xilinx or its licensors be liable 
//   for any loss of data, lost profits,cost or procurement of substitute goods or 
//   services, or for any special, incidental, consequential, or indirect damages
//   arising from the use or operation of the designs or accompanying 
//   documentation, however caused and on anytheory of liability. This limitation 
//   will apply even if Xilinx has been advised of the possibility of such damage. 
//   This limitation shall apply not-withstanding the failure of the essential 
//   purpose of any limited remedies herein.
//
//   Copyright  2004 Xilinx, Inc.
//   All rights reserved
//
//////////////////////////////////////////////////////////////////////////////////
`timescale 1 ps / 1ps

module serdes_10_to_1 (
    input clk_x5,
    input reset,
    input clk,
    input [9:0] datain,
    output iob_data_out
);

wire slave_shift_out1;
wire slave_shift_out2;

OSERDESE2 # (
    .DATA_WIDTH(10),
    .DATA_RATE_OQ("DDR"),
    .DATA_RATE_TQ("SDR"),
    .TRISTATE_WIDTH(1),
    .SERDES_MODE("MASTER")
) oserdes_m (
    .OQ(iob_data_out),
    .OFB(),
    .TQ(),
    .TFB(),
    .SHIFTOUT1(),
    .SHIFTOUT2(),
    .CLK(clk_x5),
    .CLKDIV(clk),
    .D8(datain[7]),
    .D7(datain[6]),
    .D6(datain[5]),
    .D5(datain[4]),
    .D4(datain[3]),
    .D3(datain[2]),
    .D2(datain[1]),
    .D1(datain[0]),
    .TCE(1'b0),
    .OCE(1'b1),
    .TBYTEIN(1'b0),
    .TBYTEOUT(),
    .RST(reset),
    .SHIFTIN1(slave_shift_out1),
    .SHIFTIN2(slave_shift_out2),
    .T4(1'b0),
    .T3(1'b0),
    .T2(1'b0),
    .T1(1'b0)
);

OSERDESE2 # (
    .DATA_WIDTH(10),
    .DATA_RATE_OQ("DDR"),
    .DATA_RATE_TQ("SDR"),
    .TRISTATE_WIDTH(1),
    .SERDES_MODE("SLAVE")
) oserdes_s (
    .OQ(),
    .OFB(),
    .TQ(),
    .TFB(),
    .SHIFTOUT1(slave_shift_out1),
    .SHIFTOUT2(slave_shift_out2),
    .CLK(clk_x5),
    .CLKDIV(clk),
    .D8(1'b0),
    .D7(1'b0),
    .D6(1'b0),
    .D5(1'b0),
    .D4(datain[9]),
    .D3(datain[8]),
    .D2(1'b0),
    .D1(1'b0),
    .TCE(1'b0),
    .OCE(1'b1),
    .TBYTEIN(1'b0),
    .TBYTEOUT(),
    .RST(reset),
    .SHIFTIN1(1'b0),
    .SHIFTIN2(1'b0),
    .T4(1'b0),
    .T3(1'b0),
    .T2(1'b0),
    .T1(1'b0)
);

endmodule

module srldelay # (
  parameter WIDTH = 1,       //data width
  parameter TAPS  = 4'b1111  //delay taps
)(
  input  wire                 clk,
  input  wire [(WIDTH - 1):0] data_i,
  output wire [(WIDTH - 1):0] data_o
);

  wire [3:0] dlytaps = TAPS;

  genvar i;
  generate
    for (i=0; i < WIDTH; i = i + 1) begin : srl
      SRL16E # (
        .INIT(16'h0)
      ) srl16_i  (
        .Q   (data_o[i]),
        .A0  (dlytaps[0]),
        .A1  (dlytaps[1]),
        .A2  (dlytaps[2]),
        .A3  (dlytaps[3]),
        .CE  (1'b1),
        .CLK (clk),
        .D   (data_i[i])
      );
    end
  endgenerate

endmodule

module encode # (
  parameter CHANNEL="BLUE",
  parameter MODE="HDMI"
)(
  input  wire       clkin,    // pixel clock input
  input  wire       rstin,    // async. reset input (active high)
  input  wire [7:0] vdin,     // video data inputs: expect registered
  input  wire [3:0] adin,     // audio/aux data inputs: expect registered
  input  wire       c0,       //
  input  wire       c1,       //
  input  wire       vde,      // video de input
  input  wire       ade,      // audio/aux de input
  output reg [9:0]  dout      // data outputs
);


  localparam VIDEOGBND = (CHANNEL == "BLUE") ?  10'b1011001100 : 
                         (CHANNEL == "GREEN") ? 10'b0100110011 : 10'b1011001100;

  localparam DILNDGBND = (CHANNEL == "GREEN") ? 10'b0100110011 :
                         (CHANNEL == "RED") ?   10'b0100110011 : 10'bxxxxxxxxxx; 

  ////////////////////////////////////////////////////////////
  // Counting number of 1s and 0s for each incoming pixel
  // component. Pipe line the result.
  // Register Data Input so it matches the pipe lined adder
  // output
  ////////////////////////////////////////////////////////////
  reg [3:0] n1d; //number of 1s in video din
  reg [7:0] vdin_q;

  always @ (posedge clkin) begin
    n1d <=#1 vdin[0] + vdin[1] + vdin[2] + vdin[3] + vdin[4] + vdin[5] + vdin[6] + vdin[7];

    vdin_q <=#1 vdin;
  end

  ///////////////////////////////////////////////////////
  // Stage 1: 8 bit -> 9 bit
  // Refer to DVI 1.0 Specification, page 29, Figure 3-5
  ///////////////////////////////////////////////////////
  wire decision1;

  assign decision1 = (n1d > 4'h4) | ((n1d == 4'h4) & (vdin_q[0] == 1'b0));

  wire [8:0] q_m;
  assign q_m[0] = vdin_q[0];
  assign q_m[1] = (decision1) ? (q_m[0] ^~ vdin_q[1]) : (q_m[0] ^ vdin_q[1]);
  assign q_m[2] = (decision1) ? (q_m[1] ^~ vdin_q[2]) : (q_m[1] ^ vdin_q[2]);
  assign q_m[3] = (decision1) ? (q_m[2] ^~ vdin_q[3]) : (q_m[2] ^ vdin_q[3]);
  assign q_m[4] = (decision1) ? (q_m[3] ^~ vdin_q[4]) : (q_m[3] ^ vdin_q[4]);
  assign q_m[5] = (decision1) ? (q_m[4] ^~ vdin_q[5]) : (q_m[4] ^ vdin_q[5]);
  assign q_m[6] = (decision1) ? (q_m[5] ^~ vdin_q[6]) : (q_m[5] ^ vdin_q[6]);
  assign q_m[7] = (decision1) ? (q_m[6] ^~ vdin_q[7]) : (q_m[6] ^ vdin_q[7]);
  assign q_m[8] = (decision1) ? 1'b0 : 1'b1;

  /////////////////////////////////////////////////////////
  // Stage 2: 9 bit -> 10 bit
  // Refer to DVI 1.0 Specification, page 29, Figure 3-5
  /////////////////////////////////////////////////////////
  reg [3:0] n1q_m, n0q_m; // number of 1s and 0s for q_m
  always @ (posedge clkin) begin
    n1q_m  <=#1 q_m[0] + q_m[1] + q_m[2] + q_m[3] + q_m[4] + q_m[5] + q_m[6] + q_m[7];
    n0q_m  <=#1 4'h8 - (q_m[0] + q_m[1] + q_m[2] + q_m[3] + q_m[4] + q_m[5] + q_m[6] + q_m[7]);
  end

  parameter CTRLTOKEN0 = 10'b1101010100;
  parameter CTRLTOKEN1 = 10'b0010101011;
  parameter CTRLTOKEN2 = 10'b0101010100;
  parameter CTRLTOKEN3 = 10'b1010101011;

  reg [4:0] cnt; //disparity counter, MSB is the sign bit
  wire decision2, decision3;

  assign decision2 = (cnt == 5'h0) | (n1q_m == n0q_m);
  /////////////////////////////////////////////////////////////////////////
  // [(cnt > 0) and (N1q_m > N0q_m)] or [(cnt < 0) and (N0q_m > N1q_m)]
  /////////////////////////////////////////////////////////////////////////
  assign decision3 = (~cnt[4] & (n1q_m > n0q_m)) | (cnt[4] & (n0q_m > n1q_m));

  ////////////////////////////////////
  // pipe line alignment
  ////////////////////////////////////
  reg       vde_q, vde_reg;
  reg       ade_q, ade_reg;
  reg       ade_reg_q, ade_reg_qq;
  reg       c0_q, c1_q;
  reg       c0_reg, c1_reg;
  reg [3:0] adin_q, adin_reg;
  reg [8:0] q_m_reg;

  always @ (posedge clkin) begin
    vde_q      <=#1 vde;
    vde_reg    <=#1 vde_q;

    ade_q      <=#1 ade;
    ade_reg    <=#1 ade_q;
    ade_reg_q  <=#1 ade_reg;
    ade_reg_qq <=#1 ade_reg_q;

    c0_q    <=#1 c0;
    c0_reg  <=#1 c0_q;
    c1_q    <=#1 c1;
    c1_reg  <=#1 c1_q;

    adin_q     <=#1 adin;
    adin_reg   <=#1 adin_q;

    q_m_reg    <=#1 q_m;
  end

  wire digbnd; //data island guard band period
  assign digbnd = (ade & !ade_reg) | (!ade_reg & ade_reg_qq);

  wire ade_vld;
  wire [3:0] adin_vld;

  generate
    if(CHANNEL == "BLUE") begin
      assign ade_vld = ade | ade_reg | ade_reg_qq;
      assign adin_vld = (digbnd) ? {1'b1, 1'b1, c1_reg, c0_reg} : {adin_reg[3], adin_reg[2], c1_reg, c0_reg};
    end else begin
      assign ade_vld = ade_reg;
      assign adin_vld = adin_reg;
    end
  endgenerate


  ///////////////////////////////
  // 10-bit out
  // disparity counter
  ///////////////////////////////
  always @ (posedge clkin or posedge rstin) begin
    if(rstin) begin
      dout <= 10'h0;
      cnt <= 5'h0;
    end else begin //TMDS
      if (vde_reg) begin //Video Data Period
        if(decision2) begin
          dout[9]   <=#1 ~q_m_reg[8]; 
          dout[8]   <=#1 q_m_reg[8]; 
          dout[7:0] <=#1 (q_m_reg[8]) ? q_m_reg[7:0] : ~q_m_reg[7:0];

          cnt <=#1 (~q_m_reg[8]) ? (cnt + n0q_m - n1q_m) : (cnt + n1q_m - n0q_m);
        end else begin
          if(decision3) begin
            dout[9]   <=#1 1'b1;
            dout[8]   <=#1 q_m_reg[8];
            dout[7:0] <=#1 ~q_m_reg;

            cnt <=#1 cnt + {q_m_reg[8], 1'b0} + (n0q_m - n1q_m);
          end else begin
            dout[9]   <=#1 1'b0;
            dout[8]   <=#1 q_m_reg[8];
            dout[7:0] <=#1 q_m_reg[7:0];

            cnt <=#1 cnt - {~q_m_reg[8], 1'b0} + (n1q_m - n0q_m);
          end
        end
      end else begin
        if(vde && (MODE == "HDMI"))          //video guard band period
          dout <=#1 VIDEOGBND;
        else if(ade_vld && (MODE == "HDMI")) //Aux/Audio Data Period
          case (adin_vld) //TERC4
            4'b0000: dout <=#1 10'b1010011100;
            4'b0001: dout <=#1 10'b1001100011;
            4'b0010: dout <=#1 10'b1011100100;
            4'b0011: dout <=#1 10'b1011100010;
            4'b0100: dout <=#1 10'b0101110001;
            4'b0101: dout <=#1 10'b0100011110;
            4'b0110: dout <=#1 10'b0110001110;
            4'b0111: dout <=#1 10'b0100111100;
            4'b1000: dout <=#1 10'b1011001100;
            4'b1001: dout <=#1 10'b0100111001;
            4'b1010: dout <=#1 10'b0110011100;
            4'b1011: dout <=#1 10'b1011000110;
            4'b1100: dout <=#1 10'b1010001110;
            4'b1101: dout <=#1 10'b1001110001;
            4'b1110: dout <=#1 10'b0101100011;
            default: dout <=#1 10'b1011000011;
          endcase
        else if((ade | ade_reg_qq) && (CHANNEL != "BLUE") && (MODE == "HDMI")) //data island guard band period
          dout <=#1 DILNDGBND;
        else            //preample period
          case ({c1_reg, c0_reg})
            2'b00:   dout <=#1 CTRLTOKEN0;
            2'b01:   dout <=#1 CTRLTOKEN1;
            2'b10:   dout <=#1 CTRLTOKEN2;
            default: dout <=#1 CTRLTOKEN3;
          endcase

        cnt <=#1 5'h0;
      end
    end
  end
  
endmodule

module hdmi_tx_v1_0 # (
  parameter MODE = "HDMI",		// Encoder Mode: HDMI or DVI
  parameter C_RED_WIDTH = 8,	// Width of Red Channel
  parameter C_GREEN_WIDTH = 8,  // Width of Green Channel
  parameter C_BLUE_WIDTH = 8	// Width of Blue Channel
)(
    // clock and control signals
    input pix_clk,              // Pixel Clock
    input pix_clkx5,            // Pixel Clock x5 - used for TMDS serializer
    input rst,                  // reset
    // fit to XSVI standard port
    input [C_RED_WIDTH-1:0] red,        // Red Channel Video Data
    input [C_GREEN_WIDTH-1:0] green,    // Green Channel Video Data
    input [C_BLUE_WIDTH-1:0] blue,      // Blue Channel Video Data
    input hsync,                        // Hsync Data
    input vsync,                        // Vsync Data
    input vde,                          // Video Data enable
    // audio part
    input [3:0]   aux0_din,             // Audio/Auxilary Data
    input [3:0]   aux1_din,             // Audio/Auxilary Data
    input [3:0]   aux2_din,             // Audio/Auxilary Data
    input         ade,                  // Audio/Auxilary Data Enable
    // hdmi output ports
    output TMDS_CLK_P,          // HDMI/DVI Differential Clock
    output TMDS_CLK_N,          // HDMI/DVI Differential Clock
    output  [2:0] TMDS_DATA_P,  // HDMI/DVI Differential Data Channel
    output  [2:0] TMDS_DATA_N   // HDMI/DVI Differential Data Channel
);

// Reset
wire rst;

// Padding/Truncating RGB to 24-bit color depth
wire    [7:0]   blue_din;         // Blue data in
wire    [7:0]   green_din;        // Green data in
wire    [7:0]   red_din;          // Red data in

generate
if (C_RED_WIDTH >= 8)
    assign red_din = red[C_RED_WIDTH-1 : C_RED_WIDTH-8];
else
    assign red_din = {red, {(8-C_RED_WIDTH) {1'b0}}};
endgenerate

generate
if (C_GREEN_WIDTH >= 8)
    assign green_din = green[C_GREEN_WIDTH-1 : C_GREEN_WIDTH-8];
else
    assign green_din = {green, {(8-C_GREEN_WIDTH) {1'b0}}};
endgenerate

generate
if (C_BLUE_WIDTH >= 8)
    assign blue_din = blue[C_BLUE_WIDTH-1 : C_BLUE_WIDTH-8];
else
    assign blue_din = {blue, {(8-C_BLUE_WIDTH) {1'b0}}};
endgenerate


wire    [9:0]   tmds_red;
wire    [9:0]   tmds_green;
wire    [9:0]   tmds_blue;

localparam DILNDPREAM = 4'b1010;
localparam VIDEOPREAM = 4'b1000;
localparam NULLCONTRL = 4'b0000;

// Prepare CTRL0, CTRL1 signal for Red and Green Channel
// Blue channel encodes hsync and vsync
wire ctl0, ctl1, ctl2, ctl3;

generate
if (MODE == "HDMI") begin
    reg  hdmi_ctl0, hdmi_ctl1, hdmi_ctl2, hdmi_ctl3;
    always @ (posedge pix_clk) begin
        if(vde)
            {hdmi_ctl0, hdmi_ctl1, hdmi_ctl2, hdmi_ctl3} <=#1 VIDEOPREAM;
        else if(ade)
            {hdmi_ctl0, hdmi_ctl1, hdmi_ctl2, hdmi_ctl3} <=#1 DILNDPREAM;
        else
            {hdmi_ctl0, hdmi_ctl1, hdmi_ctl2, hdmi_ctl3} <=#1 NULLCONTRL;
    end
    assign ctl0 = hdmi_ctl0;
    assign ctl1 = hdmi_ctl1;
    assign ctl2 = hdmi_ctl2;
    assign ctl3 = hdmi_ctl3;
end
else
begin
    assign ctl0 = 1'b0;
    assign ctl1 = 1'b0;
    assign ctl2 = 1'b0;
    assign ctl3 = 1'b0;
end
endgenerate

wire [7:0] blue_dly, green_dly, red_dly;
wire [3:0] aux0_dly, aux1_dly, aux2_dly;
wire       hsync_dly, vsync_dly, vde_dly, ade_dly;

// Local Delays to data and audio signal. A delay of 10 clock cycle is needed to insert preamble
// and guardband according to HDMI specification 1.3. In DVI mode, there is no such requirement,
// so a delay of 1 clock would be more than enough.
localparam SRL_DELAY = (MODE == "HDMI") ? 4'b1010 : 4'b0001;

srldelay # (
    .WIDTH(40),
    .TAPS(SRL_DELAY)
) srldly_0 (
    .data_i({blue_din, green_din, red_din, aux0_din, aux1_din, aux2_din, hsync, vsync, vde, ade}),
    .data_o({blue_dly, green_dly, red_dly, aux0_dly, aux1_dly, aux2_dly, hsync_dly, vsync_dly, vde_dly, ade_dly}),
    .clk(pix_clk)
);

// Data Channel Encoders
encode # (
  .CHANNEL("BLUE"),
  .MODE(MODE)
)encb (
    .clkin  (pix_clk),
    .rstin  (rst),
    .vdin   (blue_dly),
    .adin   (aux0_dly),
    .c0     (hsync_dly),
    .c1     (vsync_dly),
    .vde    (vde_dly),
    .ade    (ade_dly),
    .dout   (tmds_blue)) ;

encode # (
  .CHANNEL("GREEN"),
  .MODE(MODE)
) encg (
    .clkin  (pix_clk),
    .rstin  (rst),
    .vdin   (green_dly),
    .adin   (aux1_dly),
    .c0     (ctl0),
    .c1     (ctl1),
    .vde    (vde_dly),
    .ade    (ade_dly),
    .dout   (tmds_green)) ;

encode # (
  .CHANNEL("RED"),
  .MODE(MODE)
) encr (
    .clkin  (pix_clk),
    .rstin  (rst),
    .vdin   (red_dly),
    .adin   (aux2_dly),
    .c0     (ctl2),
    .c1     (ctl3),
    .vde    (vde_dly),
    .ade    (ade_dly),
    .dout   (tmds_red)) ;

wire [9:0] tmdsclkint;
assign tmdsclkint = 10'b1111100000;

wire [2:0] TMDSINT;
wire tmdsclk;

// Channel 10-to-1 serializer
serdes_10_to_1 serial_b(
    .clk_x5(pix_clkx5),
    .reset(rst),
    .clk(pix_clk),
    .datain(tmds_blue),
    .iob_data_out(TMDSINT[0]));

serdes_10_to_1 serial_g(
    .clk_x5(pix_clkx5),
    .reset(rst),
    .clk(pix_clk),
    .datain(tmds_green),
    .iob_data_out(TMDSINT[1]));

serdes_10_to_1 serial_r(
    .clk_x5(pix_clkx5),
    .reset(rst),
    .clk(pix_clk),
    .datain(tmds_red),
    .iob_data_out(TMDSINT[2]));

serdes_10_to_1 serial_clk(
    .clk_x5(pix_clkx5),
    .reset(rst),
    .clk(pix_clk),
    .datain(tmdsclkint),
    .iob_data_out(tmdsclk));

// Output Differential Buffer
OBUFDS #(
    .IOSTANDARD("TMDS_33")
)
OBUFDS_B(
    .O(TMDS_DATA_P[0]),
    .OB(TMDS_DATA_N[0]),
    .I(TMDSINT[0]));

OBUFDS #(
    .IOSTANDARD("TMDS_33")
)
OBUFDS_G(
    .O(TMDS_DATA_P[1]),
    .OB(TMDS_DATA_N[1]),
    .I(TMDSINT[1]));
OBUFDS #(
    .IOSTANDARD("TMDS_33")
)
OBUFDS_R(
    .O(TMDS_DATA_P[2]),
    .OB(TMDS_DATA_N[2]),
    .I(TMDSINT[2]));

OBUFDS #(
    .IOSTANDARD("TMDS_33")
)
OBUFDS_CLK(
    .O(TMDS_CLK_P),
    .OB(TMDS_CLK_N),
    .I(tmdsclk));

endmodule
