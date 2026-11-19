# BunnerHub Etech roadmap

Started 2026-10-26. Ordered by value: each item came out of a real debugging session (Prototype, Sleeping Dogs, the Play Time crash). Status: ☐ not started · ◐ in progress · ☑ done.

---

## 1. Crash reporter ☑

**Why:** debugging Prototype and Sleeping Dogs needed a PC, adb and an hour of digging to find one line in `/sdcard/wine.log`. The Play Time screen crash could not be diagnosed at all without a phone on the cable.

**What:**
- **Game sessions:** when a game session ends, find the session's part of `/sdcard/wine.log` (Wine appends every session to it), look for the decisive lines (`Unhandled page fault`, `Unhandled exception`, `RtlpWaitForCriticalSection ... timed out`, `Device lost`/`VK_ERROR_DEVICE_LOST`), and save a report zip in `/sdcard/BunnerHub-Etech/reports/`:
  - that part of `wine.log`
  - GameHub's launch parameters (`files/pcLaunchLog/launchLog<gameId>.txt`)
  - the game's settings (`shared_prefs/pc_g_setting<gameId>.xml`)
  - the BhWineLog session log when present
  - a `summary.txt` with game, time, play time and the matched lines
- **Detection without relying on a clean exit:** the `:wine` process writes a "session open" marker when the game starts (game id and name, `wine.log` offset, start time). The launcher finalizes any marker whose `:wine` process is gone, so force-stops and crashes are covered too.
- **Tell the user:** a dialog in the launcher, "Prototype stopped: Unhandled page fault at FFFFFFFE", with the report's path.
- **App crashes:** an uncaught-exception handler in both processes writes `app-crash-<time>.txt` (stack trace, process, version, device) to the same folder.
- **Reports screen:** ☰ → Crash Reports lists the reports, with Share (Android share sheet) and Delete.

**Done when:** a forced crash (e.g. Sleeping Dogs LE) produces a report and the dialog without a PC; an exception in a BunnerHub screen leaves a stack trace file.

**Status (2026-10-26):** done and device-tested (`f78af58`). Prototype without its split-lock fix → dialog "Prototype™: Hang (lock wait timed out)" with the exact log line; report zip has summary, wine.log slice, launch params, settings, capture log, logcat; Crash Reports list, Share (Android share sheet) work; a healthy session makes no report. The first device test found false "stopped right after starting" reports, fixed in `f78af58`. Not yet tested: an app-crash file (needs an app crash).

## 2. Release vs debug builds ☑

**Why:** CI builds are debuggable, so Android shows the "debuggable app" warning on every app start and on settings screens. Debuggable builds are also what makes `adb shell run-as` work, which these debugging sessions relied on.

**What:** CI produces two APKs: the normal one, non-debuggable (no warning), and a `-debug` one, debuggable, for adb sessions. Same signing key, so either installs over the other.

**Status (2026-10-26):** implemented as a switch rather than two APKs per push (half the CI time): pushes build non-debuggable APKs; a manual run builds a debuggable `…-Etech-debug.apk`:
`gh workflow run build.yml -R eladharels/BunnerHub-Etech --ref main -f debuggable=true`. Device-tested: the push build has no DEBUGGABLE flag and no warning; the debug build allows `run-as`; both install over each other.

## 3. Known-fix profiles ☑

**Status (2026-10-27):** v1 done and device-tested (`extension/BhKnownFixes.java`): Prototype shows "Known fix applied"; Remove → amber "Known fix: Black screen at start", Apply → green again (env var written back); Sleeping Dogs on the DE exe correctly shows no chip. The LE-exe chip is untested (the game no longer points at LE).


**Why:** fixes found by debugging get lost: Prototype needs `FEX_STRICTINPROCESSSPLITLOCKS=1`; Sleeping Dogs only runs from the DE executable (the LE one is a modified exe whose anti-tamper check kills it).

**What:** a bundled list of fixes matched by game (Steam app id, exe name/hash). When a game matches, game settings show "Known fix available" with one-tap Apply (env vars, startup exe, settings), and a note for things that can't be applied automatically. Entries record where the fix came from (PROGRESS_LOG entry).

**Findings (2026-10-26, from the phone):** a game's env vars are the string `pc_ls_environment_variable` in `shared_prefs/pc_g_setting<gameId>.xml`, entries joined by commas (`A=1,B=2`); the stock dialog writes it. The startup exe is *not* in those prefs (it lives in GameHub's game database `ux_db`), so v1 applies env vars and shows the exe change as an instruction.

## 4. Play Time extras ◐

**Status (2026-10-26):** sessions and 7/30-day totals device-tested (a 2-minute Prototype session shows as "Mon 26 Oct, 20:29  2 m"). Export/import of play time not yet tried on the phone.


**What:**
- per-session history (start, length) per game, shown on the Play Time screen
- "this week" and "last 30 days" totals
- include the totals in Export/Import Config so they survive a reinstall

## 6. In-game trainer ☑

**Status (2026-10-27):** v1 implemented: `extension/BhTrainer.java` (engine), `extension/BhTrainerPanel.java` (UI), sidebar tab via `patches/smali_classes16/com/xj/winemu/sidebar/BhTrainerFragment.smali` + `BhTrainerClickListener.smali`, `WineActivityDrawerContent` (`U()` branch, click wiring), layout `winemu_activitiy_settings_layout.xml`, icon `sidebar_trainer.xml`. Device-tested in Prototype (2026-10-27): tab opens and finds the game; first scan of 459 MB in ~2 s with the game at 59 FPS (1280 → 443 results); next scan narrows (443 → 442 → 441); "no results" message; a 49-result list shows addresses with live values. Elad then used it in Prototype to find and change XP: "it worked" (2026-10-27).

**Why:** replaying finished single-player games for fun: change values live (XP, money, items, health) from the in-game menu, Cheat Engine style.

**Feasibility (2026-10-27, `BhMemProbe`, device-tested):** the app's `:wine` process (SELinux `untrusted_app`, same categories as the game) can read **and write** the game's memory via `/proc/<pid>/mem`: Prototype had 460 MB of writable memory below 4 GB (32-bit game under FEX; guest addresses map 1:1). Read 16 bytes, wrote them back, read-back matched.

**Plan:** a Trainer panel in the in-game sidebar: exact-value scan (4-byte int first; float, 2-byte, 1-byte, double), "next scan" to narrow, set value, freeze (rewrite every ~100 ms), named per-game entries. Single-player only; addresses may move between launches.

## 7. FSR upscaling ◐

**Status (2026-11-17):** v1 implemented, not yet device-tested. Option 1 from the upscaling discussion: Wine's own FSR 1 (`WINE_FULLSCREEN_FSR`, Proton / GE-Proton fullscreen hack) switched per game from a new **FSR** sidebar tab. `extension/BhFsr.java` (launch env + Wine support check + DXVK render-size reader), `extension/BhFsrPanel.java` (UI), `BhWineLog.env/command` (hook), sidebar tab via `BhFsrFragment.smali` + `BhFsrClickListener.smali`, `WineActivityDrawerContent` (`U()` branch, click wiring), icon `sidebar_fsr.xml`.

**To test on the device:** does the Status line say the Wine build supports FSR (which Wine builds do)? Does removing `WINE_DISABLE_FULLSCREEN_HACK` break anything (GameHub sets it for every game)? With a lower in-game fullscreen resolution, does "Game renders at" drop and FPS rise? Do the render-size modes work?

**Device test (2026-11-17, S24 Ultra, Firewatch / DXVK):** the FSR tab works (after making the sidebar icon column scrollable), but Status says the game's Wine build has no FSR, so FSR stayed off and no `fsr-*.log` was written. Not yet tried: other Wine/Proton builds offered in the game's settings. Possible small addition: show the Wine folder and files the check looked at, to rule out a false "no".

**Key finding:** the Wine screen is 1280x720 and the display 2340x1080, so GameHub already stretches 720p to the screen with a plain resize. Wine's FSR could only upscale *up to* the Wine screen size, so the real gain is in that final stretch. GameHub's "Super Resolution" is only CAS sharpening (`CASEffect` in its ReShade config), not upscaling.

**Next (on hold until Elad is back, December):** our own Vulkan layer that does FSR 1 / Snapdragon GSR at present time and replaces GameHub's final stretch. It needs no per-game work (spatial, whole frame; HUD/text upscaled too) and works with any Wine for DXVK / VKD3D games, probably OpenGL too (Zink → Vulkan). Work: native C++ (NDK) built in CI, find where GameHub does the final stretch (`libGameScopeVK` DirectRendering present path or the X server renderer), a per-game on/off and quality setting, several device test rounds. OptiScaler per game (DLSS/XeSS/FSR 2 inputs) stays a later option.

## 8. Cheats ◐

**Status (2026-11-19):** step 2 implemented: unknown-value / decreased / increased / changed / unchanged searches in the Trainer, so health bars can be found and frozen (infinite health). Elad found the manual search too much work on a phone, so step 4 (guided Health finder with Lock all) and step 3 v1 (Cheat Engine table import, value/pointer entries) followed the same day. Elad doesn't want ready-made trainers (too much work to download and run). Table import device-tested and working (2026-11-19). Then Elad asked for trainers inside the in-game menu (mockup made): step 1 (download from inside the game, auto-start in the game's Wine, Start/Stop) built 2026-11-19; step 2 (a switch panel pressing the trainer's keys, names read from the download page, trainer window hidden) built the same day. Device-tested 2026-11-19: Prototype 2's FLiNG trainer works end to end (Find online, .rar via 7-Zip, start in the game's Wine, window hidden, game back, cheats). Sleeping Dogs (.NET trainer, Wine Mono installed) still to be reported. Next for tables: Auto Assembler scripts (needs an x86 assembler for code caves, AOB scans, and a device test of whether patched x86 code takes effect under FEX).

**Plan (discussed 2026-11-19):**
1. Elad tests a ready-made trainer (e.g. FLiNG) inside the game's Wine via the sidebar Task Manager → Launch. Unknowns: code patching under FEX, hotkeys via the on-screen keyboard.
2. ☑ Unknown-value searches.
3. Pointer paths (module + offsets) so saved values survive restarts; then import Cheat Engine tables (`.CT`, e.g. from FearLess Revolution): address/pointer entries first, Auto Assembler scripts only if a test shows that patching x86 code works under FEX (translated code may not see the change). Users import tables themselves; none are bundled. Single-player only.

## 5. Housekeeping ☑

**Status (2026-10-27):** done at Elad's request. The work branch replaced `main` (which only held a one-line "Initial commit"); CI builds on pushes to `main`. Builds are signed with Elad's own release key (RSA 4096, alias `bunnerhub-etech`, cert SHA-256 `D5:F2:A2:7C:…:F4:DA`), stored as repo secrets `SIGNING_KEYSTORE_B64` / `SIGNING_KEYSTORE_PASSWORD` / `SIGNING_KEY_ALIAS`; the keystore itself lives only outside the repo (Elad's PC, to be backed up). Without the secrets (forks) CI falls back to the public test key and names the APK `…-testkey.apk`. Switching keys needed one uninstall.


- **Make the work branch `main`.** `main` holds only "Initial commit", so this replaces it.
- **Own signing key** as a GitHub secret instead of the public test key. Android refuses to update an app signed with a different key: switching means uninstalling once and losing the app's data unless it is backed up first.
