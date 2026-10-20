# Combined EE290 VCU118 reference bitstream

[TetheredEE290VCU118Config.bit](./TetheredEE290VCU118Config.bit) targets the
VCU118 (VU9P), combining the Shuttle/Saturn/Atlas chip and coreless bringup
host at 50 MHz, with internal SerialTL and host UART-TSI at 115200 baud.

Built with Vivado 2021.2 on October 16, 2026. The matching FPGA sources are
committed as `9f60bf6d`. The complete build exited successfully and reported
passing setup/hold timing; the board has not been programmed or tested.
See the [build guide](../../docs/Prototyping/Tethered-EE290-VCU118.md) for
the build commands, timing results, and remaining constraint warnings.

To verify the downloaded file, place it beside [SHA256SUMS](./SHA256SUMS)
and run:

```bash
sha256sum -c SHA256SUMS
```
