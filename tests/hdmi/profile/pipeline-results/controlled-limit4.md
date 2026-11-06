# Scanout profiling comparison

Queued-read limit: 1 → 4.
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
| periodic-20us | 28.296 → 34.570 | +22.17% | 71202 → 18874 | +73.49% |
| random-20-seed-1 | 36.833 → 36.833 | +0.00% | 0 → 0 | N/A (zero baseline) |
| random-20-seed-42 | 36.833 → 36.833 | +0.00% | 0 → 0 | N/A (zero baseline) |
| random-20-seed-12345 | 36.833 → 36.833 | +0.00% | 0 → 0 | N/A (zero baseline) |
| random-50-seed-1 | 36.833 → 36.833 | +0.00% | 0 → 0 | N/A (zero baseline) |
| random-50-seed-42 | 36.833 → 36.833 | +0.00% | 0 → 0 | N/A (zero baseline) |
| random-50-seed-12345 | 36.833 → 36.833 | +0.00% | 0 → 0 | N/A (zero baseline) |
| blackout-100us | 36.655 → 36.710 | +0.15% | 1488 → 1024 | +31.15% |
| blackout-1ms | 34.736 → 35.356 | +1.78% | 17488 → 12320 | +29.55% |
| latency-600 | 0.000 → 36.833 | N/A (zero baseline) | 307200 → 0 | +100.00% |
| latency-800 | 0.000 → 0.000 | N/A (zero baseline) | 307200 → 307200 | +0.00% |
| latency-1000 | 0.000 → 0.000 | N/A (zero baseline) | 307200 → 307200 | +0.00% |
| latency-1200 | 0.000 → 0.000 | N/A (zero baseline) | 307200 → 307200 | +0.00% |
| latency-1600 | 0.000 → 0.000 | N/A (zero baseline) | 307200 → 307200 | +0.00% |

Largest tested zero-underflow model latency: 125 → 600 cycles.
Change in sampled passing-latency bound: +380.00%.
These are sampled bounds, not proven maxima. If the candidate passes the entire sweep, extend the latency range before claiming its limit.
Wire-byte throughput is recorded separately in results.json and is not used as a substitute for useful displayed pixels.
