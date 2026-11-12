# ThermoHammer for the Microsoft Store (MSIX)

```
msix/build.sh                 # x64 + arm64 → msix/out/ThermoHammer_<version>_x64.msix and _arm64.msix
msix/build.sh --store         # the same, but refuses to run until msix/identity.env holds the identity Partner Center reserved
msix/build.sh --skip-publish x64   # repack one architecture from the last publish (msix/.cache)
```

The version comes from `<Version>` in `ThermoHammer.csproj` (2.0.4 → package 2.0.4.0: the Store reserves the last part). Bump it
together with the phones. `fetch_makemsix.sh` builds Microsoft's makemsix once (git + cmake + Xcode tools) into `msix/.tools`.
The packages are **unsigned**: the Store signs what it accepts.

## First submission

1. Partner Center → Apps and games → New product → **MSIX or PWA app** → reserve the name *ThermoHammer*.
2. Product identity page → copy **Package/Identity/Name**, **Package/Identity/Publisher** and **Publisher display name** into
   `identity.env`, then `msix/build.sh --store`.
3. Submission → Packages → upload both `msix/out/ThermoHammer_<version>_x64.msix` and `_arm64.msix` (one submission takes several packages and serves each PC its own). Partner Center shows the capabilities: `runFullTrust`
   (a classic desktop app packaged as MSIX — it asks for a short justification: "Win32 app that stress-tests the CPU/GPU and
   needs direct hardware access") and `internetClient` (the leaderboard).
4. Store listing: description, screenshots (PC: at least 1366×768 — `ThermoHammer.exe --screenshot` renders the WPF tree, but WebView2
   surfaces come out black, so capture the desktop), the app icon (300×300 and 1080×1080 from `CpuThrottlingTest/Assets.xcassets/.../icon_1024.png`),
   the **privacy policy URL** (the app sends benchmark results to the leaderboard server), the age rating questionnaire, pricing (free).
5. Notes for certification: the app needs a PC with a Direct3D 11 GPU for its graphics tests; a section a PC can't run is shown as
   "NOT RUN" with the reason (check this on the test PCs before submitting).

## Trying a package on a PC before the Store sees it

The loose-file route needs no certificate: copy `msix/.cache/stage-<arch>` to the PC, turn on Developer Mode
(Settings → System → For developers) and run in PowerShell

```
Add-AppxPackage -Register .\stage-x64\AppxManifest.xml
```

The VC++ runtime framework (`Microsoft.VCLibs.140.00.UWPDesktop`) comes from the Store on its own; on a PC without Store access,
install it from https://aka.ms/Microsoft.VCLibs.x64.14.00.Desktop.appx (arm64: `.arm64.`).
