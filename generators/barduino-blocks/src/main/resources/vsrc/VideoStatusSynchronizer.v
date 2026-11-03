// Single-bit CDC: three discrete destination-clock FFs, never an SRL.
module VideoStatusSynchronizer(input clock, input reset, input d, output q);
  (* ASYNC_REG = "TRUE", SHREG_EXTRACT = "NO" *) reg [2:0] stages = 3'b000;
  always @(posedge clock) begin
    if (reset) stages <= 3'b000;
    else stages <= {stages[1:0], d};
  end
  assign q = stages[2];
endmodule
