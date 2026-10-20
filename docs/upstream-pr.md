# Upstream PR to joakimeriksson/fm1-emu

Per the plan (§2.6): one self-contained PR, no other coordination, opened after M0 so it carries
on-device measurements. Nothing in Harbor depends on it being merged: the submodule runs with
`patches/0001-fm1-core-enable-the-aarch64-jit-on-android.patch` applied, and that patch is the
PR's commit (`git diff` of it against the pinned commit, so `tools/apply-patches.sh` takes it).

- **Status:** opened 2026-10-20 from `markterm/fm1-emu`, branch `android-jit` (one commit on
  `7856393`, the pinned commit).
- **Title:** fm1-core: run the AArch64 JIT on Android too
- **Evidence** (Pixel 7 Pro, Tensor G2, Android 16; `docs/m0-results/Pixel_7_Pro.tsv`):
  - fm1-core's tests cross-built and run on the phone: 36 passed, the three `jit::tests` among
    them.
  - JIT vs interpreted at 240 MHz: Felucca 1.52× vs 0.42×, Salt 1.78× vs 0.43×, Etch 1.22× vs
    0.48×. Interpreted, nothing keeps up even at 120 MHz.
  - Inside Harbor (`untrusted_app`): JIT on, Felucca at 1.34× real time at 240 MHz with audio.
  - CTR_EL0 reads IDC = 1, DIC = 0 on all eight cores, like Apple silicon, so the data-side
    clean still hasn't been tested on a core that needs it. The PR says so.

## If it changes

When the PR is merged, bump the submodule past it and delete the patch. If review asks for
changes, make them on the `android-jit` branch and regenerate the patch from it
(`git diff 7856393 android-jit`) so Harbor keeps building what upstream will get.

## Follow-ups, separate and only if wanted upstream

- When `CodeCache::new` fails, `Cpu::run_fast` calls `Jit::new` again (a 64 MB `mmap`) for
  every block. Remembering the failure would make the refused-execmem fallback cheap. Harbor
  sidesteps this by probing once (`harbor_engine::machine::jit_usable`) and booting with the JIT
  off.
- An `aarch64-linux-android` `cargo check` job in CI (offered in the PR, not included).
