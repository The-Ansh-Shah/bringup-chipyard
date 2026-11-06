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
 logic dmaAV,dmaDR,dmaDV,dmaAR;
`ifndef PROFILE_HAS_D_READY
 assign dmaDR=1;
`endif
 bit presented=0;
 logic [31:0] dmaAddr;
 logic [2:0] dmaSource,responseSource;
 logic [2:0] sources[64];
 integer outstandingLimit=1,reorder=0;
 longint reorderedBursts=0;
`ifndef PROFILE_HAS_SOURCE_IDS
 assign dmaSource=0;
`endif
 logic [63:0] dmaData;
 integer latency=0, randomPct=0, seed=1, period=0, pauseCycles=0;
 integer blackoutAt=0, blackoutCycles=0, warmup=1, frames=2;
 integer head=0,tail=0,count=0,beat=0;
 longint cycle=0, measurementStart=0, measurementEnd=0;
 longint accepted[64], eligible[64];
 logic [31:0] addresses[64];
 logic [31:0] rng=1;
 bit measuring=0, finished=0;
 longint requests=0, bytesReceived=0, completions=0, firstLatencySum=0, completionSum=0;
 longint firstResponses=0, maxFirst=0, outstandingSum=0, maxOutstanding=0, samples=0;
 longint pushed=0,popped=0, occupancySum=0,minOccupancy=64,maxOccupancy=0;
 longint activePixels=0, missingPixels=0, wrongPixels=0, goodPixels=0;
 longint frameMissing[256];
 longint requestSequence=0, responseStalls=0;
 longint firstRecovered=-1;
 integer framePixels[256];
 wire [63:0] elapsed = measuring ? cycle-measurementStart : 0;
 wire blocked = measuring && ((period>0 && (elapsed%64'(period))<64'(pauseCycles)) ||
   (blackoutCycles>0 && elapsed>=64'(blackoutAt) && elapsed<(64'(blackoutAt)+64'(blackoutCycles))));
 function automatic logic [15:0] color(input integer pixel);
   integer x,y,mix;
   x=pixel%640; y=pixel/640;
   mix=(x*73)^(y*151)^(x>>3)^(y<<5);
   return 16'(mix)|16'h0001;
 endfunction
 assign dmaAR=count<64;
 always_comb begin
   dmaDV=count>0 && presented;
   responseSource=sources[head];
   for(integer j=0;j<4;j++)
     dmaData[j*16+:16]=color(integer'((addresses[head]-32'h81000000)/2)+beat*4+j);
 end
 always @(posedge clk) begin : memory_model
   longint occ,firstDelay;
   bit takeA,takeD,takeEnq;
   logic [31:0] capturedAddress,tmpAddress;
   logic [2:0] capturedSource,tmpSource;
   longint tmpAccepted,tmpEligible;
   integer chosen,idx;
   takeA=dmaAV && dmaAR; takeD=dmaDV && dmaDR;
   capturedAddress=dmaAddr;capturedSource=dmaSource;
   takeEnq=dut.fifo.io_enq_valid && dut.fifo.io_enq_ready;
   #1;
   cycle=cycle+1;
   if(reset) begin head=0;tail=0;count=0;beat=0;presented=0; end
   else begin
     if(measuring && !finished) begin
       samples++;outstandingSum+=64'(count);
       if(64'(count)>maxOutstanding)maxOutstanding=64'(count);
       occ=pushed-popped;
       if(occ<0 || occ>64)$fatal(1,"FIFO occupancy out of bounds: %0d",occ);
       occupancySum+=occ;
       if(occ<minOccupancy)minOccupancy=occ;
       if(occ>maxOccupancy)maxOccupancy=occ;
       if(count>0 && !dmaDV)responseStalls++;
     end
     if(takeEnq)pushed++;
     if(takeD)begin
       if(measuring && !finished)begin
         bytesReceived+=8;
         if(beat==0)begin
           firstResponses++;firstDelay=cycle-accepted[head];firstLatencySum+=firstDelay;
           if(firstDelay>maxFirst)maxFirst=firstDelay;
         end
       end
       if(beat==7)begin
         if(measuring && !finished)begin completions++;completionSum+=cycle-accepted[head];end
         head=(head+1)%64;beat=0;
       end else beat++;
     end
     if(takeA)begin
       if(capturedAddress!==32'h81000000+32'((requestSequence%9600)*64))
         $fatal(1,"DMA sequence mismatch request=%0d address=%h",requestSequence,capturedAddress);
       sources[tail]=capturedSource;
       addresses[tail]=capturedAddress;accepted[tail]=cycle;eligible[tail]=cycle+64'(latency)+1+(reorder!=0 ? (requestSequence%4)*17 : 0);
       tail=(tail+1)%64;requestSequence++;
       if(measuring && !finished)requests++;
     end
     // Queue count updates use the response beat value before increment.
     count=count+integer'(takeA)-integer'(takeD && beat==0);
     // Once offered, hold D valid/data through backpressure. Stalls delay only new offers.
     if((!presented || takeD) && beat==0 && reorder!=0 && count>1)begin
       chosen=head;
       for(integer k=1;k<count;k++)begin
         idx=(head+k)%64;
         if(cycle>=eligible[idx])chosen=idx;
       end
       if(chosen!=head)begin
         tmpAddress=addresses[head];addresses[head]=addresses[chosen];addresses[chosen]=tmpAddress;
         tmpSource=sources[head];sources[head]=sources[chosen];sources[chosen]=tmpSource;
         tmpAccepted=accepted[head];accepted[head]=accepted[chosen];accepted[chosen]=tmpAccepted;
         tmpEligible=eligible[head];eligible[head]=eligible[chosen];eligible[chosen]=tmpEligible;
         reorderedBursts++;
       end
     end
     if(!presented || takeD)
       presented=count>0 && cycle>=eligible[head] && !blocked && (rng%100)>=32'(randomPct);
     rng={rng[30:0],rng[31]^rng[21]^rng[1]^rng[0]};
   end
 end
 ClockSinkDomain_2 dut(
  .auto_clock_in_clock(clk),.auto_clock_in_reset(reset),.io_pxclk(px),.io_pxclkx5(fast),.io_videoReset(videoReset),
  .auto_dma_out_a_ready(dmaAR),.auto_dma_out_a_valid(dmaAV),.auto_dma_out_a_bits_address(dmaAddr),
`ifdef PROFILE_HAS_D_READY
  .auto_dma_out_d_ready(dmaDR),
`endif
  .auto_dma_out_d_valid(dmaDV),.auto_dma_out_d_bits_denied(1'b0),
`ifdef PROFILE_HAS_SOURCE_IDS
  .auto_dma_out_a_bits_source(dmaSource),.auto_dma_out_d_bits_source(responseSource),
`endif
  .auto_dma_out_d_bits_data(dmaData),.auto_dma_out_d_bits_corrupt(1'b0),
  .auto_in_a_ready(ar),.auto_in_a_valid(av),.auto_in_a_bits_opcode(opcode),.auto_in_a_bits_size(2'd3),
  .auto_in_a_bits_source('0),.auto_in_a_bits_address(addr),.auto_in_a_bits_mask(8'hff),.auto_in_a_bits_data(wdata),
  .auto_in_d_ready(dr),.auto_in_d_valid(dv),.auto_in_d_bits_data(rdata),
  .auto_in_d_bits_opcode(),.auto_in_d_bits_size(),.auto_in_d_bits_source(),
  .io_hdmiOut_TMDS_CLK_P(),.io_hdmiOut_TMDS_CLK_N(),.io_hdmiOut_TMDS_DATA_P(),.io_hdmiOut_TMDS_DATA_N());

 task automatic writeReg(input logic [28:0] a,input logic [63:0] d);
   @(negedge clk);addr=a;wdata=d;opcode=0;av=1;
   do @(posedge clk);while(!ar);
   @(negedge clk);av=0;
   repeat(8)@(posedge clk);
 endtask
 always @(posedge px)begin : pixel_monitor
   logic [15:0] expected;
   logic [23:0] rgb;
   if(!dut.tx.rst)begin
     if(dut.fifo.io_deq_valid && dut.fifo.io_deq_ready)popped++;
     if(measuring && !finished && dut.tx.vde)begin
       activePixels++;framePixels[dut.frame]++;
       expected=color((integer'(dut.v)-35)*640+integer'(dut.h)-144);
       rgb={{expected[4:0],expected[4:2]},{expected[10:5],expected[10:9]},{expected[15:11],expected[15:13]}};
       if(!dut.validPixel)begin
         missingPixels++;frameMissing[dut.frame]++;
         if({dut.tx.red,dut.tx.green,dut.tx.blue}!==24'h0)$fatal(1,"underflow did not produce black");
       end else begin
         goodPixels++;
         if({dut.tx.red,dut.tx.green,dut.tx.blue}!==rgb)begin
           wrongPixels++;
           $fatal(1,"wrong valid pixel frame=%0d x=%0d y=%0d",dut.frame,dut.h-144,dut.v-35);
         end
         if(blackoutCycles>0 && firstRecovered<0 && elapsed>=(64'(blackoutAt)+64'(blackoutCycles)))firstRecovered=64'(elapsed)-(64'(blackoutAt)+64'(blackoutCycles));
       end
     end
   end
 end
 initial begin : run
   integer fd;
   string outputFile;
   if(!$value$plusargs("limit=%d",outstandingLimit))outstandingLimit=1;
   if(!$value$plusargs("reorder=%d",reorder))reorder=0;
   if(!$value$plusargs("latency=%d",latency))latency=0;
   if(!$value$plusargs("random_pct=%d",randomPct))randomPct=0;
   if(!$value$plusargs("seed=%d",seed))seed=1;
   if(!$value$plusargs("period=%d",period))period=0;
   if(!$value$plusargs("pause=%d",pauseCycles))pauseCycles=0;
   if(!$value$plusargs("blackout_at=%d",blackoutAt))blackoutAt=0;
   if(!$value$plusargs("blackout_cycles=%d",blackoutCycles))blackoutCycles=0;
   if(!$value$plusargs("frames=%d",frames))frames=2;
   if(!$value$plusargs("output=%s",outputFile))outputFile="profile.json";
   rng=32'(seed);if(rng==0)rng=1;
   #1000;reset=0;videoReset=0;
   repeat(100)@(posedge clk);
   writeReg(29'h10081010,64'h81000000);
`ifdef PROFILE_HAS_SOURCE_IDS
   writeReg(29'h10081018,64'(outstandingLimit));
`endif
   writeReg(29'h10081008,1);
   wait(dut.frame==8'(warmup));
   @(negedge clk);measurementStart=cycle;measuring=1;
   wait(dut.frame==8'(warmup+frames));
   @(negedge clk);finished=1;measurementEnd=cycle;
   if(activePixels!=64'(frames)*307200)$fatal(1,"wrong measured pixel count");
   for(integer i=warmup;i<warmup+frames;i++)
     if(framePixels[i]!=307200)$fatal(1,"wrong per-frame pixel count");
   fd=$fopen(outputFile,"w");if(fd==0)$fatal(1,"cannot open metrics output");
   $fwrite(fd,"{\n");
   $fwrite(fd,"\"configured_limit\":%0d,\"reordered_bursts\":%0d,\n",outstandingLimit,reorderedBursts);
   $fwrite(fd,"\"model_latency\":%0d,\"model_random_pct\":%0d,\"model_seed\":%0d,\n",latency,randomPct,seed);
   $fwrite(fd,"\"cycles\":%0d,\"requests\":%0d,\"response_bytes\":%0d,\"completions\":%0d,\n",measurementEnd-measurementStart,requests,bytesReceived,completions);
   $fwrite(fd,"\"first_responses\":%0d,\"first_latency_sum\":%0d,\"max_first_latency_cycles\":%0d,\"completion_latency_sum\":%0d,\n",firstResponses,firstLatencySum,maxFirst,completionSum);
   $fwrite(fd,"\"outstanding_sum\":%0d,\"max_outstanding\":%0d,\"samples\":%0d,\n",outstandingSum,maxOutstanding,samples);
   $fwrite(fd,"\"occupancy_sum\":%0d,\"min_occupancy_words\":%0d,\"max_occupancy_words\":%0d,\n",occupancySum,minOccupancy,maxOccupancy);
   $fwrite(fd,"\"active_pixels\":%0d,\"missing_pixels\":%0d,\"wrong_pixels\":%0d,\"good_pixels\":%0d,\n",activePixels,missingPixels,wrongPixels,goodPixels);
   $fwrite(fd,"\"first_recovered_cycles\":%0d,\"response_stall_cycles\":%0d,\"missing_by_frame\":[",firstRecovered,responseStalls);
   for(integer i=warmup;i<warmup+frames;i++)$fwrite(fd,"%s%0d",i==warmup?"":",",frameMissing[i]);
   $fwrite(fd,"]}\n");$fclose(fd);
   $display("PROFILE latency=%0d missing=%0d/%0d maxOutstanding=%0d",latency,missingPixels,activePixels,maxOutstanding);
   $finish;
 end
 initial begin #300000000; $fatal(1,"profile timeout");end
endmodule
