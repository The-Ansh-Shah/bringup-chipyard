# Scanout baseline: simulation measurements

100 MHz system; approximately 25.1786 MHz pixel clock; 64 FIFO words; one outstanding read.
One warmup frame, two measured frames. Decimal MB/s. Synthetic memory model; no DDR/coherence/serializer model.

| Workload | DMA MB/s | Useful pixel MB/s | Missing pixels/frame | First beat latency (cycles) | Avg FIFO words | Max reads |
|---|---:|---:|---:|---:|---:|---:|
| latency-0 | 36.833 | 36.833 | 0 | 2.00 | 63.75 | 1 |
| latency-25 | 36.833 | 36.833 | 0 | 27.00 | 63.53 | 1 |
| latency-50 | 36.833 | 36.833 | 0 | 52.00 | 63.06 | 1 |
| latency-75 | 36.833 | 36.833 | 0 | 77.00 | 62.36 | 1 |
| latency-100 | 36.833 | 36.833 | 0 | 102.00 | 61.41 | 1 |
| latency-110 | 36.833 | 36.833 | 0 | 112.00 | 60.96 | 1 |
| latency-115 | 36.833 | 36.833 | 0 | 117.00 | 60.73 | 1 |
| latency-120 | 36.833 | 36.833 | 0 | 122.00 | 58.79 | 1 |
| latency-125 | 36.833 | 36.833 | 0 | 127.00 | 56.37 | 1 |
| latency-140 | 36.833 | 36.833 | 0 | 142.00 | 49.12 | 1 |
| latency-160 | 36.833 | 0.291 | 304774 | 162.00 | 2.47 | 1 |
| latency-200 | 30.332 | 0.000 | 307200 | 202.00 | 1.05 | 1 |
| latency-400 | 15.572 | 0.000 | 307200 | 402.00 | 0.54 | 1 |
| periodic-5us | 36.833 | 36.833 | 0 | 60.57 | 61.14 | 1 |
| periodic-20us | 36.833 | 28.238 | 71688 | 95.02 | 38.70 | 1 |
| random-20-seed-1 | 36.833 | 36.833 | 0 | 52.34 | 63.04 | 1 |
| random-20-seed-42 | 36.833 | 36.833 | 0 | 52.34 | 63.04 | 1 |
| random-20-seed-12345 | 36.833 | 36.833 | 0 | 52.33 | 63.04 | 1 |
| random-50-seed-1 | 36.833 | 36.833 | 0 | 53.00 | 63.00 | 1 |
| random-50-seed-42 | 36.833 | 36.833 | 0 | 53.00 | 63.00 | 1 |
| random-50-seed-12345 | 36.833 | 36.833 | 0 | 53.00 | 63.00 | 1 |
| blackout-100us | 36.833 | 36.664 | 1412 | 52.52 | 62.79 | 1 |
| blackout-1ms | 36.833 | 34.900 | 16122 | 57.21 | 60.09 | 1 |

Largest tested fixed latency with zero missing pixels: 140 model cycles.
This is a sampled sweep boundary, not a proven maximum. Response bytes include stale/discarded data; useful bandwidth counts only correctly matched displayed pixels.
No optimization has been applied, so no percentage improvement is claimed.
