# Scanout profiling comparison

Queued-read limit: 1 → 8.
FIFO payload capacity: 512 → 512 bytes; additional reorder storage: 512 → 512 bytes.
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
| latency-140 | 24.870 → 36.833 | +48.10% | 99778 → 0 | +100.00% |
| latency-160 | 0.000 → 36.833 | N/A (zero baseline) | 307200 → 0 | +100.00% |
| latency-200 | 0.000 → 36.833 | N/A (zero baseline) | 307200 → 0 | +100.00% |
| latency-400 | 0.000 → 36.833 | N/A (zero baseline) | 307200 → 0 | +100.00% |
| periodic-5us | 36.833 → 36.833 | +0.00% | 0 → 0 | N/A (zero baseline) |
| periodic-20us | 28.296 → 36.616 | +29.40% | 71202 → 1813 | +97.45% |
| random-20-seed-1 | 36.833 → 36.833 | +0.00% | 0 → 0 | N/A (zero baseline) |
| random-20-seed-42 | 36.833 → 36.833 | +0.00% | 0 → 0 | N/A (zero baseline) |
| random-20-seed-12345 | 36.833 → 36.833 | +0.00% | 0 → 0 | N/A (zero baseline) |
| random-50-seed-1 | 36.833 → 36.833 | +0.00% | 0 → 0 | N/A (zero baseline) |
| random-50-seed-42 | 36.833 → 36.833 | +0.00% | 0 → 0 | N/A (zero baseline) |
| random-50-seed-12345 | 36.833 → 36.833 | +0.00% | 0 → 0 | N/A (zero baseline) |
| blackout-100us | 36.655 → 36.720 | +0.18% | 1488 → 939 | +36.90% |
| blackout-1ms | 34.736 → 35.363 | +1.81% | 17488 → 12256 | +29.92% |
| latency-600 | 0.000 → 36.833 | N/A (zero baseline) | 307200 → 0 | +100.00% |
| latency-800 | 0.000 → 36.833 | N/A (zero baseline) | 307200 → 0 | +100.00% |
| latency-1000 | 0.000 → 36.833 | N/A (zero baseline) | 307200 → 0 | +100.00% |
| latency-1200 | 0.000 → 36.833 | N/A (zero baseline) | 307200 → 0 | +100.00% |
| latency-1600 | 0.000 → 0.000 | N/A (zero baseline) | 307200 → 307200 | +0.00% |

Largest tested zero-underflow model latency: 125 → 1200 cycles.
Change in sampled passing-latency bound: +860.00%.
These are sampled bounds, not proven maxima. If the candidate passes the entire sweep, extend the latency range before claiming its limit.
Wire-byte throughput is recorded separately in results.json and is not used as a substitute for useful displayed pixels.
