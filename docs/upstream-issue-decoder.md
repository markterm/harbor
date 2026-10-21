# Upstream issue, prepared: undecoded 0x?064 words

Not filed: opening it publishes under the owner's GitHub account, so it waits for their go-ahead.
The OberVave fix (`patches/0002`) is now fm1-emu#33, and DUELL's (`0004`) #35: the issue is for
the words that are left (CTL-1, MC-1, 386DX, Kawa). Before filing, drop OberVave's row and point
at those PRs.

---

Title: Undecoded 0x?064 instructions halt OberVave, CTL-1, MC-1 and 386DX

At master (7856393, which includes the decoder-fuzz fixes), four Hub firmwares stop on an
`UnknownInstruction` whose first halfword is `0xf064` or `0xe864`. fm1-isa knows a few words of
this family (`r_mov_sp_3` 3e00f064, `sp_mov_r_4` 4e80f064, `r_i_and_r_3` 06dbe864,
`r_i_xor_r` 0001e864), each matched on the whole word, so I think these are the same operations
with register fields the table hasn't seen.

| Firmware (Hub package) | Core | pc | bytes | when |
|---|---|---|---|---|
| obervave-1.5.3 | 0 | 0x020016ce | 64 e8 5f bc | ~8 ms after any key is released |
| ctl-1.0 | 0 | 0x02000b58 | 64 f0 00 23 | 587 ms after boot |
| mc1-1.2.0 | 0 (core 1 running) | 0x0200ed8c | 64 f0 00 0e | 153 ms after boot |
| 386dx-1.0.2 | 1 | 0x02021310 | 64 f0 00 ae | 513 ms after boot |

`fm1-emu --check-decode` reports all four as `undecoded`. In OberVave the instruction is a
branch target (`020016c0: if (r10 == 0) goto 020016ce`), and the same word also appears at
020016c8, so it's real code rather than a jump into data.

Reproduce OberVave: boot obervave-1.5.3.fwsc at 240 MHz, press and release any key (matrix
key 14-40), and the CPU stops at 0x020016ce. Holding a key, or pressing a panel button, doesn't
trigger it. I reproduced it interpreted on x86-64, and it was also seen on an Android phone.

Found while testing Harbor (an Android host for fm1-emu). Thanks for the emulator.
