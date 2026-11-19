/*
 * bh_winctl.exe - window helper for BunnerHub Etech's trainer programs,
 * run inside the game's Wine (built by CI with mingw-w64, shipped in the APK
 * as assets/bunnerhub-etech/bh_winctl.exe).
 *
 * GameHub's Wine has no taskbar or Alt+Tab, so a trainer window that comes up
 * over the game can't be put away. The trainer's cheats are hotkeys and keep
 * working with its window hidden, so the app hides it and brings the game
 * back.
 *
 *   bh_winctl hide  <match>   hide every visible window of matching processes
 *   bh_winctl show  <match>   show and activate their titled windows
 *   bh_winctl focus <match>   restore and activate the largest window of them
 *
 * <match> is a case-insensitive part of the process image path
 * (e.g. "bh_trainer" or "sdhdship.exe"). Exit code 0 when a window was
 * found and handled, 1 when none, 2 on bad arguments.
 */
#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif
#include <windows.h>
#include <wchar.h>

enum { CMD_HIDE, CMD_SHOW, CMD_FOCUS };

static int g_cmd;
static wchar_t g_match[512];
static int g_count;
static HWND g_best;
static LONGLONG g_best_area;

static BOOL image_matches(DWORD pid)
{
    HANDLE h = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid);
    wchar_t path[1024];
    DWORD n = 1024;
    BOOL ok;
    if (!h) return FALSE;
    ok = QueryFullProcessImageNameW(h, 0, path, &n);
    CloseHandle(h);
    if (!ok) return FALSE;
    _wcslwr(path);
    return wcsstr(path, g_match) != NULL;
}

static BOOL CALLBACK each_window(HWND w, LPARAM lp)
{
    DWORD pid = 0;
    RECT r;
    LONGLONG area;
    (void)lp;
    GetWindowThreadProcessId(w, &pid);
    if (!pid || pid == GetCurrentProcessId() || !image_matches(pid)) return TRUE;

    switch (g_cmd) {
    case CMD_HIDE:
        if (IsWindowVisible(w)) {
            ShowWindow(w, SW_HIDE);
            g_count++;
        }
        break;
    case CMD_SHOW:
        GetWindowRect(w, &r);
        if (GetWindowTextLengthW(w) > 0 && r.right - r.left > 50 && r.bottom - r.top > 50) {
            ShowWindow(w, SW_SHOWNORMAL);
            SetForegroundWindow(w);
            g_count++;
        }
        break;
    case CMD_FOCUS:
        if (!IsWindowVisible(w) && !IsIconic(w)) break;
        GetWindowRect(w, &r);
        area = (LONGLONG)(r.right - r.left) * (LONGLONG)(r.bottom - r.top);
        if (IsIconic(w)) area = 1; /* a minimised window still counts */
        if (!g_best || area > g_best_area) {
            g_best = w;
            g_best_area = area;
        }
        break;
    }
    return TRUE;
}

int wmain(int argc, wchar_t **argv)
{
    if (argc < 3) return 2;
    if (!_wcsicmp(argv[1], L"hide")) g_cmd = CMD_HIDE;
    else if (!_wcsicmp(argv[1], L"show")) g_cmd = CMD_SHOW;
    else if (!_wcsicmp(argv[1], L"focus")) g_cmd = CMD_FOCUS;
    else return 2;
    wcsncpy(g_match, argv[2], 511);
    g_match[511] = 0;
    _wcslwr(g_match);

    EnumWindows(each_window, 0);

    if (g_cmd == CMD_FOCUS && g_best) {
        if (IsIconic(g_best)) ShowWindow(g_best, SW_RESTORE);
        else ShowWindow(g_best, SW_SHOW);
        BringWindowToTop(g_best);
        SetForegroundWindow(g_best);
        g_count = 1;
    }
    return g_count > 0 ? 0 : 1;
}
