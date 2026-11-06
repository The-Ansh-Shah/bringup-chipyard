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
 logic [31:0] requestFrameBase;
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
     dmaData[j*16+:16]=color(integer'((addresses[head] & 32'hfffff)/2)+beat*4+j) ^ (addresses[head]>=32'h81100000 ? 16'hffff : 16'h0000);
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
       if(requestSequence%9600==0)requestFrameBase=capturedAddress;
       if((requestFrameBase!=32'h81000000 && requestFrameBase!=32'h81100000) ||
          capturedAddress!==requestFrameBase+32'((requestSequence%9600)*64))
         $fatal(1,"DMA sequence mismatch request=%0d address=%h",requestSequence,capturedAddress);
       sources[tail]=capturedSource;
       addresses[tail]=capturedAddress;accepted[tail]=cycle;eligible[tail]=cycle+64'(latency)+1+(reorder!=0 ? (requestSequence%4)*97 : 0);
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

 integer swaps=8,late=0;
 integer startedFrames=0,completedFrames=0,framePattern=-1,previousPattern=-1;
 longint pixelCycles=0,firstFrameCycle=0,lastFrameCycle=0;
 longint minFramePeriod=999999999,maxFramePeriod=0,validFrames=0;
 longint txResetPulses=0,repeatedFrames=0,framePixelCount=0;
 longint acknowledged=0,firstAckCycle=0,lastAckCycle=0;
 bit armed=0,previousTxReset=1;
 integer targetPattern=0;
 logic [31:0] control;
 function automatic logic [23:0] rgb(input logic [15:0] c);
   return {{c[4:0],c[4:2]},{c[10:5],c[10:9]},{c[15:11],c[15:13]}};
 endfunction
 task automatic readReg(input logic [28:0] a,output logic [31:0] value);
   bit acceptedA,acceptedD;
   acceptedA=0;acceptedD=0;
   @(negedge clk);addr=a;wdata=0;opcode=4;av=1;
   while(!acceptedA || !acceptedD)begin
     @(posedge clk);
     if(av && ar)acceptedA=1;
     if(dv && dr)begin value=rdata[31:0];acceptedD=1;end
     @(negedge clk);
     if(acceptedA)av=0;
   end
 endtask
 always @(posedge px)begin : observe
   logic [15:0] c;
   logic [23:0] got;
   longint intervalCycles;
   pixelCycles++;
   if(armed && dut.tx.rst && !previousTxReset)txResetPulses++;
   previousTxReset=dut.tx.rst;
   if(!dut.tx.rst && dut.tx.vde)begin
     if(dut.h==144 && dut.v==35 && dut.validPixel)begin
       if(!armed)begin armed=1;firstFrameCycle=pixelCycles;end
       if(lastFrameCycle!=0)begin
         intervalCycles=pixelCycles-lastFrameCycle;
         if(intervalCycles<minFramePeriod)minFramePeriod=intervalCycles;
         if(intervalCycles>maxFramePeriod)maxFramePeriod=intervalCycles;
`ifdef PAGEFLIP_RTL
         if(intervalCycles!=420000)$fatal(1,"video frame period changed: %0d",intervalCycles);
`endif
       end
       lastFrameCycle=pixelCycles;
       previousPattern=framePattern;framePattern=-1;
       startedFrames++;framePixelCount=0;
     end
     if(armed)begin
       framePixelCount++;
       c=color((integer'(dut.v)-35)*640+integer'(dut.h)-144);
       got={dut.tx.red,dut.tx.green,dut.tx.blue};
       if(!dut.validPixel)missingPixels++;
       else begin
         if(framePattern<0)begin
           if(got==rgb(c))framePattern=0;
           else if(got==rgb(c^16'hffff))framePattern=1;
           else $fatal(1,"unknown framebuffer pixel");
         end
         if(got!==rgb(c^(framePattern==1 ? 16'hffff:16'h0000)))
           $fatal(1,"tearing/wrong pixel frame=%0d x=%0d y=%0d",startedFrames,dut.h-144,dut.v-35);
         goodPixels++;
       end
       if(dut.h==783 && dut.v==514)begin
         if(framePixelCount!=307200)$fatal(1,"wrong pixel count");
         completedFrames++;validFrames++;
         if(previousPattern==framePattern)repeatedFrames++;
       end
     end
   end
 end
 initial begin : present
   string outputFile;
   integer fd,doneFrames;
   if(!$value$plusargs("latency=%d",latency))latency=0;
   if(!$value$plusargs("random_pct=%d",randomPct))randomPct=0;
   if(!$value$plusargs("reorder=%d",reorder))reorder=0;
   if(!$value$plusargs("late=%d",late))late=0;
   if(!$value$plusargs("swaps=%d",swaps))swaps=8;
   if(!$value$plusargs("output=%s",outputFile))outputFile="pageflip.json";
   #1000;reset=0;videoReset=0;
   repeat(100)@(posedge clk);
   writeReg(29'h10081010,64'h81000000);
   writeReg(29'h10081018,4);
   writeReg(29'h10081008,1);
   wait(startedFrames==1);
   for(integer i=0;i<swaps;i++)begin
     targetPattern=(i+1)%2;
`ifdef PAGEFLIP_RTL
     if(late!=0)wait(dut.v==514 && dut.h>=784);
     writeReg(29'h10081020,targetPattern==1 ? 64'h81100000:64'h81000000);
     writeReg(29'h10081028,1);
     control=0;
     while(control<32'(i+1))readReg(29'h10081030,control);
     if(framePattern!=targetPattern)$fatal(1,"ack precedes presentation");
`else
     doneFrames=completedFrames;
     wait(completedFrames>doneFrames);
     writeReg(29'h10081008,0);
     wait(count==0 && dut.state==0);
     repeat(100)@(posedge clk);
     requestSequence=0;pushed=0;popped=0;
     writeReg(29'h10081010,targetPattern==1 ? 64'h81100000:64'h81000000);
     doneFrames=startedFrames;
     writeReg(29'h10081008,1);
     wait(startedFrames>doneFrames);
`endif
     acknowledged++;
     if(firstAckCycle==0)firstAckCycle=cycle;
     lastAckCycle=cycle;
   end
   doneFrames=startedFrames;
   wait(completedFrames>=doneFrames);
   if(missingPixels!=0)$fatal(1,"underflow during presentation: %0d",missingPixels);
`ifdef PAGEFLIP_RTL
   if(txResetPulses!=0)$fatal(1,"transmitter reset on flip");
   if(late==0 && repeatedFrames!=0)$fatal(1,"missed presentation deadline");
`else
   if(txResetPulses!=64'(swaps))$fatal(1,"legacy reset count mismatch");
`endif
   if(reorder!=0 && reorderedBursts==0)$fatal(1,"reorder workload never reordered responses");
   fd=$fopen(outputFile,"w");if(fd==0)$fatal(1,"output open failed");
   $fwrite(fd,"{\"flips\":%0d,\"started_frames\":%0d,\"completed_frames\":%0d,",acknowledged,startedFrames,completedFrames);
   $fwrite(fd,"\"tx_resets\":%0d,\"missing_pixels\":%0d,\"good_pixels\":%0d,\"repeated_frames\":%0d,",txResetPulses,missingPixels,goodPixels,repeatedFrames);
   $fwrite(fd,"\"min_frame_pixel_cycles\":%0d,\"max_frame_pixel_cycles\":%0d,",minFramePeriod,maxFramePeriod);
   $fwrite(fd,"\"first_ack_cycle\":%0d,\"last_ack_cycle\":%0d,\"reordered_bursts\":%0d}\n",firstAckCycle,lastAckCycle,reorderedBursts);
   $fclose(fd);
   $display("PASS: flips=%0d tx resets=%0d missing=%0d repeated=%0d frame period=%0d..%0d",acknowledged,txResetPulses,missingPixels,repeatedFrames,minFramePeriod,maxFramePeriod);
   $finish;
 end
 initial begin #600000000;$fatal(1,"pageflip timeout");end
endmodule
