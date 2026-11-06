# Scanout profiling comparison

Queued-read limit: 1 → 1.
FIFO payload capacity: 512 → 512 bytes; additional reorder storage: 0 → 512 bytes.
An original-to-pipelined comparison includes extra buffering; same-design limit comparisons hold physical storage constant.

| Workload | Useful MB/s: before → after | Useful throughput change | Missing/frame: before → after | Underflow reduction |
|---|---:|---:|---:|---:|
| latency-0 | 36.833 → 36.833 | +0.00% | 0 → 0 | N/A (zero baseline) |
| latency-25 | 36.833 → 36.833 | +0.00% | 0 → 0 | N/A (zero baseline) |
| latency-50 | 36.833 → 36.833 | +0.00% | 0 → 0 | N/A (zero baseline) |
| latency-75 | 36.833 → 36.833 | +0.00% | 0 → 0 | N/A (zero baseline) |
| latency-100 | 36.833 → 36.833 | +0.00% | 0 → 0 | N/A (zero baseline) |
| latency-110 | 36.833 → 36.833 | +0.00% | 0 → 0 | N/A (zero baseline) |
| latency-115 | 36.833 → 36.833 | +0.00% | 0 → 0 | N/A (zero baseline) |
| latency-120 | 36.833 → 36.833 | +0.00% | 0 → 0 | N/A (zero baseline) |
| latency-125 | 36.833 → 36.833 | +0.00% | 0 → 0 | N/A (zero baseline) |
| latency-140 | 36.833 → 24.870 | -32.48% | 0 → 99778 | N/A (zero baseline) |
| latency-160 | 0.291 → 0.000 | -100.00% | 304774 → 307200 | -0.80% |
| latency-200 | 0.000 → 0.000 | N/A (zero baseline) | 307200 → 307200 | +0.00% |
| latency-400 | 0.000 → 0.000 | N/A (zero baseline) | 307200 → 307200 | +0.00% |
| periodic-5us | 36.833 → 36.833 | +0.00% | 0 → 0 | N/A (zero baseline) |
| periodic-20us | 28.238 → 28.296 | +0.21% | 71688 → 71202 | +0.68% |
| random-20-seed-1 | 36.833 → 36.833 | +0.00% | 0 → 0 | N/A (zero baseline) |
| random-20-seed-42 | 36.833 → 36.833 | +0.00% | 0 → 0 | N/A (zero baseline) |
| random-20-seed-12345 | 36.833 → 36.833 | +0.00% | 0 → 0 | N/A (zero baseline) |
| random-50-seed-1 | 36.833 → 36.833 | +0.00% | 0 → 0 | N/A (zero baseline) |
| random-50-seed-42 | 36.833 → 36.833 | +0.00% | 0 → 0 | N/A (zero baseline) |
| random-50-seed-12345 | 36.833 → 36.833 | +0.00% | 0 → 0 | N/A (zero baseline) |
| blackout-100us | 36.664 → 36.655 | -0.02% | 1412 → 1488 | -5.38% |
| blackout-1ms | 34.900 → 34.736 | -0.47% | 16122 → 17488 | -8.48% |
| latency-600 | 0.000 → 0.000 | N/A (zero baseline) | 307200 → 307200 | +0.00% |
| latency-800 | 0.000 → 0.000 | N/A (zero baseline) | 307200 → 307200 | +0.00% |
| latency-1000 | 0.000 → 0.000 | N/A (zero baseline) | 307200 → 307200 | +0.00% |
| latency-1200 | 0.000 → 0.000 | N/A (zero baseline) | 307200 → 307200 | +0.00% |
| latency-1600 | 0.000 → 0.000 | N/A (zero baseline) | 307200 → 307200 | +0.00% |

Largest tested zero-underflow model latency: 140 → 125 cycles.
Change in sampled passing-latency bound: -10.71%.
These are sampled bounds, not proven maxima. If the candidate passes the entire sweep, extend the latency range before claiming its limit.
Wire-byte throughput is recorded separately in results.json and is not used as a substitute for useful displayed pixels.
