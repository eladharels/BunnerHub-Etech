# BunnerHub Etech: guide for agents

Read this first. It says what the repo is, how it is built and tested, what has been done, and how to work with the owner. Details live in the files it points to.

## What this repo is

**BunnerHub Etech** is Elad's personal fork of [BannerHub](https://github.com/The412Banner/bannerhub) (by The412Banner). BannerHub is a patched **GameHub** Android app (GameHub runs Windows PC games on Android phones with Wine + FEX/Box64 + DXVK/VKD3D + a Turnip/Adreno Vulkan driver). There is **no source code** for GameHub: the build decompiles the stock APK with apktool, applies smali patches and resources, adds our own Java code as an extra dex, and rebuilds and signs the APK.

- Package `com.bunnerhub.etech`, label "BunnerHub Etech", one build variant (`Etech`).
- Everything below `README.md`'s first note is still upstream's README (BannerHub features). Our own features have sections there too (FSR Upscaling, In-Game Trainer, Crash Reports, Play Time, Known Fixes, Offline Game Launch…).
- Owner's phone: Samsung Galaxy S24 Ultra (Snapdragon 8 Gen 3, Adreno 750), display 2340x1080, not rooted. Games used for testing: Prototype, Prototype 2, Sleeping Dogs, GTA IV, Firewatch, BioShock.

## Where things are

| Path | What |
|------|------|
| `.github/workflows/build.yml` | The whole build: download base APK → apktool decompile → copy `patches/` → inline Python smali patches → rename package → build native tools → rebuild → compile `extension/` to dex → zipalign → sign → upload artifact |
| `patches/` | Files copied over the decompiled APK: `smali*/` (hooks and new smali classes), `res/` (layouts, drawables), `AndroidManifest.xml` |
| `extension/*.java` | Our Java code, package `app.revanced.extension.gamehub`, compiled by CI (javac + d8) into an extra dex. Smali hooks call it with `invoke-static` |
| `tools/vklayer/` | Our Vulkan layer (`bh_vklayer.c`, FSR 1 compute shaders in `shaders/` compiled to SPIR-V headers, AMD FidelityFX headers in `ffx/`, MIT) built with the NDK into `assets/bunnerhub-etech/libbh_vklayer.so` |
| `tools/bh_winctl.c` | Tiny Windows program (mingw) used by trainer programs: hides the trainer window, sends its hotkeys, brings the game back |
| `PROGRESS_LOG.md` | Newest first. One entry per change: what, why, how it was tested, device results. **Read the top few entries before starting** |
| `docs/ROADMAP.md` | Feature list with status (☐ ◐ ☑), open problems and ideas |
| `gamehub_reports/`, `BANNERHUB_MASTER_MAP.md` | Upstream's reverse-engineering notes on GameHub classes (useful for finding hook points) |

Obfuscated GameHub classes are referenced by their smali names (e.g. `com.xj.winemu.WineActivity`, `WineActivityDrawerContent`, `com.winemu.core.DirectRendering`). Two processes matter: the launcher (main process) and `:wine` (the game screen, in-game sidebar, BhPlayTime). SharedPreferences are cached per process, so settings that both read live in files (e.g. `files/bh_saves_auto_off`).

## Building

- Every push to `main` builds. Work branches build only by manual dispatch:
  `mcp__github__actions_run_trigger` with `workflow_id: build.yml`, `ref: <branch>`. Inputs: `debuggable` (adb `run-as` works, Android shows a warning) and `testkey`.
- The APK is an artifact named `BunnerHub-Etech-<ref>-Etech.apk`, **kept for 1 day only**. Give Elad the direct artifact link:
  `https://github.com/eladharels/BunnerHub-Etech/actions/runs/<run id>/artifacts/<artifact id>` (list artifacts with `actions_list` → `list_workflow_run_artifacts`). Elad installs it over the existing app.
- Signing uses Elad's release key from repo secrets (`SIGNING_KEYSTORE_B64`, `SIGNING_KEYSTORE_PASSWORD`, `SIGNING_KEY_ALIAS`). Never commit keys. Without secrets CI signs with the public test key (`…-testkey.apk`), which cannot update Elad's install.
- A build takes about 5–6 minutes.

## Testing

Elad tests on the phone and sends logs. Before asking for a device test, test what can be tested here:

- **Java:** compile `extension/` with javac against a stub/android.jar to catch errors. For logic, copy the class to the scratchpad, replace Android-only calls (Environment, Log) with plain stand-ins, and run it with a small main.
- **Vulkan layer:** builds and runs locally on Linux with lavapipe: `Xvfb :99` + `vkcube` (or a small test app), `VK_LAYER_PATH`/an implicit manifest and `BH_VKLAYER=1`, plus the Khronos validation layer. Check status file output and validation messages.
- **Smali:** apktool rebuild of the patched tree is the real test; CI does it.

Logs on the phone (Elad sends these), all under `/sdcard/BunnerHub-Etech/`:

| File | Written by | When |
|------|-----------|------|
| `logs/wine-<date>-<time>.log` | `BhWineLog` | Only for games whose **Wine Debug Parameters** (game settings) are not empty. Use `err+all,+seh` for freezes. Env + Wine/FEX output of every process |
| `logs/upscaler-sessions.log` | `BhUpscalerLog` | Every 10 s while the upscaler layer is on (Report only or FSR): settings, FPS, GPU %, power, temperatures |
| `logs/upscaler.log` | `BhVkSurfaceScale` | Surface scale changes for FSR output |
| `logs/trainer-launch.txt`, `trainer-output.log`, `winctl.log` | `BhTrainerProgram` | Trainer program start commands and output |
| `reports/` | `BhCrashReporter` | Crash/hang report zips, `app-crash-*.txt` |
| `saves/<game>/`, `screenshots/`, `trainers/<game>/` | save backup, upscaler screenshot, trainers | |

A log from the wrong game or the wrong day is common: check the launch command and date inside before analysing it.

## What we have built (newest first, details in PROGRESS_LOG.md)

- **Own Vulkan upscaler layer** (`VK_LAYER_BH_upscale`), FSR tab in the in-game sidebar: FSR 1 (EASU + RCAS) from the game's resolution to the display or 1080p/900p/720p, 5 sharpness levels, settings change live (control file polled twice a second, swapchain recreated via `VK_ERROR_OUT_OF_DATE_KHR`), screenshot button (PNG to `screenshots/`), per-game performance history card. **Needs Native Rendering+ → Force Enable** in the game's settings for full-screen output (GameHub's DirectRendering surface is scaled by `BhVkSurfaceScale`). Device-tested in GTA IV, Prototype 2, Sleeping Dogs.
- **Automatic save backup** after a game closes (last 3 auto + 5 manual zips), restore from the game's page.
- **In-game sidebar cleanup:** Wine FSR section removed; root-only Sustained Perf / Max Adreno toggles hidden.
- **Trainer** (sidebar tab): memory search (exact/unknown/changed…), freeze, guided health finder, Cheat Engine table import (value/pointer entries), find tables online, and **trainer programs** (e.g. FLiNG) downloaded and run inside the game's Wine with a switch panel that presses their hotkeys. Works in Prototype, Prototype 2, Sleeping Dogs (.NET trainers via a one-time Wine Mono install).
- **Offline game launch** (online checks never block play), Bluetooth earbuds 2x audio fix, sidebar focus highlight fix, faster launcher start on weak networks, USB controller popup fix (from upstream).
- **Known fixes** chip on the game page (e.g. Prototype needs `FEX_STRICTINPROCESSSPLITLOCKS=1`), **crash reporter** + Crash Reports screen, **Play Time** (sessions, 7/30-day totals), **Wine/FEX log capture**, release vs debug builds, own signing key.
- From upstream and kept: Component Manager with an in-app driver/DXVK downloader (Elad imports new Turnip drivers this way), **AI Frame Generation** (Performance tab; works on Elad's phone), Konkr HUD, RTS controls, Task Manager, Dual Battery.

**Tried and removed:** heat control (frame cap stepping down with temperature) and overheat warning, 2026-11-23 → removed 2026-11-25 at Elad's request after Prototype 2 freezes were wrongly blamed on it (the freezes also happen without it). Don't bring it back unless Elad asks.

## Open problems (where to pick up)

1. **Prototype 2 freezes for good** after a while (game stays frozen until restarted). Not caused by our layer, heat control, Native Rendering+ or the frame rate. Suspects: the FLiNG trainer (infinite health / infinite power patch game code under FEX), the game itself, or its Wine/driver/DXVK settings. Next: set `err+all,+seh` in **Prototype 2's own** Wine Debug Parameters (it has none, so no Wine log was ever written), play with the trainer off, collect the log from a freeze. Also ask whether sound continues and the sidebar opens during the freeze. A newer Turnip driver is also worth a try.
2. **BioShock (2007) hangs on its splash screen** after DXVK's "Process set as DPI aware". Suggested and not yet reported back: "Disable Graphics Enhancement Plugin" (ReShade is on, `ENABLE_RESHADE=1`), another DXVK version or WineD3D, another Wine build.
3. **Phone access from an agent:** Elad plans a session from their PC (Claude app with Remote Control, phone on adb) so an agent can read logcat and logs directly. The PC "Elad-pc" was listed but did not offer folders to cloud sessions (likely needs a Claude app update). For app-private files use a debuggable build (`debuggable: true`).

See `docs/ROADMAP.md` for the full list and later ideas.

## Working with Elad

- Plain, short language. Elad is not a programmer; explain what to tap and what to send back. Numbered test steps, one variable at a time.
- When a build is ready, send **the download link**.
- Elad tests on the device and reports; record device results in `PROGRESS_LOG.md` and `docs/ROADMAP.md`.
- Ask before big or outward-facing steps; Elad decides which features to build ("Do 1 and 2").
- Not rooted: avoid features that need root.
- Single-player use only for trainer/cheat features.

## Conventions

- Develop on the session's work branch, push there; merge to `main` only when Elad asks. `main` = what Elad has installed and trusts.
- Every change: a `PROGRESS_LOG.md` entry at the top (`### [feat|fix|revert] — title (date)`, then what/why/tested), and update `docs/ROADMAP.md` status. Update the README section when a feature changes.
- Review your own change before handing it over; a "fixes from code review" commit is normal here.
- Commit messages: what and why, no model names.
- Keep upstream code paths intact where possible (smali hooks are small and call into `extension/`), so upstream fixes can still be merged.
