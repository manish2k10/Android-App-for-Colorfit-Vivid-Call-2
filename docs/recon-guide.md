# recon-guide.md — DEPRECATED

The original "Phase 0" reverse-engineering plan (capture HCI snoop logs with
nRF Connect / sideload NoiseFit Prime from APKPure) is no longer needed.

We bypassed it entirely by:

1. Pulling the NoiseFit Prime app's cache via `adb` to the host
   (`C:\Users\user\com.noisefit.prime\cache\…`) — gave us 47 MB of app-side
   protocol logs.
2. Decompiling the app's installed APK with **jadx GUI** to read the
   vendor SDK source directly.

The actual vendor protocol decode happened from those two artefacts — see
`docs/protocol-research.md` for the opcode map and `docs/opcode-reference.md`
for the human-readable command list.

This file is kept only because earlier drafts referenced it. New work
should start from the protocol-research docs, not this guide.
