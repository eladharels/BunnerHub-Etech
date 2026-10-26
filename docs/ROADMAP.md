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
`gh workflow run build.yml -R eladharels/BunnerHub-Etech --ref ccr-5c179b34-5ojklf -f debuggable=true`. Device-tested: the push build has no DEBUGGABLE flag and no warning; the debug build allows `run-as`; both install over each other.

## 3. Known-fix profiles ☐

**Why:** fixes found by debugging get lost: Prototype needs `FEX_STRICTINPROCESSSPLITLOCKS=1`; Sleeping Dogs only runs from the DE executable (the LE one is a modified exe whose anti-tamper check kills it).

**What:** a bundled list of fixes matched by game (Steam app id, exe name/hash). When a game matches, game settings show "Known fix available" with one-tap Apply (env vars, startup exe, settings), and a note for things that can't be applied automatically. Entries record where the fix came from (PROGRESS_LOG entry).

**Findings (2026-10-26, from the phone):** a game's env vars are the string `pc_ls_environment_variable` in `shared_prefs/pc_g_setting<gameId>.xml`, entries joined by commas (`A=1,B=2`); the stock dialog writes it. The startup exe is *not* in those prefs (it lives in GameHub's game database `ux_db`), so v1 applies env vars and shows the exe change as an instruction.

## 4. Play Time extras ◐

**Status (2026-10-26):** sessions and 7/30-day totals device-tested (a 2-minute Prototype session shows as "Mon 26 Oct, 20:29  2 m"). Export/import of play time not yet tried on the phone.


**What:**
- per-session history (start, length) per game, shown on the Play Time screen
- "this week" and "last 30 days" totals
- include the totals in Export/Import Config so they survive a reinstall

## 5. Housekeeping ☐ (needs Elad's decision)

- **Make the work branch `main`.** `main` holds only "Initial commit", so this replaces it.
- **Own signing key** as a GitHub secret instead of the public test key. Android refuses to update an app signed with a different key: switching means uninstalling once and losing the app's data unless it is backed up first.
