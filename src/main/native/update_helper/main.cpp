// Korosoft-Core Windows update helper.
//
// The running Minecraft JVM keeps the mod jar open on Windows, so the jar cannot
// be replaced while the game is alive (the old in-process move/copy failed with
// "being used by another process"). This helper is spawned BEFORE the game
// exits, waits for the parent Java process to die, then swaps the jar and
// writes the apply result into a .result file the next boot can read.
//
// Usage: korosoft-update-helper.exe <parentPid> <installedJar> <newJar> <resultFile>
//   - waits until the process <parentPid> no longer exists (max 2 minutes)
//   - moves <installedJar> aside to <installedJar>.bak
//   - moves <newJar> (the freshly downloaded <jar>.update) into place at <installedJar>
//   - on failure restores the installed jar from .bak so the game still boots
//   - writes resultFile as "OK" or "ERR:<message>"
//
// CONTRACT NOTE (2026-08-10): <installedJar> is the jar that EXISTS right now, the
// one Fabric loaded. The Java side used to pass a never-created "<jar>.old" here,
// which made this helper take its "nothing to replace" path: it deleted the
// downloaded jar and reported OK, so Windows players rebooted on the old build
// silently. ModUpdater.replaceJar now passes the installed jar.
//
// The helper is a plain console process with no console window attached; it only
// touches the filesystem and the result file, never stdout, so it survives the
// parent's death without pipe issues.

#include <windows.h>
#include <chrono>
#include <cstdio>
#include <fstream>
#include <sstream>
#include <string>
#include <thread>

namespace {

bool processExists(DWORD pid) {
    HANDLE h = OpenProcess(SYNCHRONIZE, FALSE, pid);
    if (h == NULL) return false; // gone or no permission -> treat as gone
    DWORD code = 0;
    if (!GetExitCodeProcess(h, &code)) {
        CloseHandle(h);
        return true; // cannot tell -> assume still alive
    }
    CloseHandle(h);
    return code == STILL_ACTIVE;
}

void writeResult(const std::wstring& file, const std::wstring& msg) {
    FILE* f = _wfopen(file.c_str(), L"wb");
    if (!f) return;
    fwprintf(f, L"%ls", msg.c_str());
    fclose(f);
}

bool fileExists(const std::wstring& path) {
    DWORD attr = GetFileAttributesW(path.c_str());
    return attr != INVALID_FILE_ATTRIBUTES && !(attr & FILE_ATTRIBUTE_DIRECTORY);
}

} // namespace

int wmain(int argc, wchar_t** argv) {
    if (argc < 5) return 2;
    DWORD parentPid = static_cast<DWORD>(_wtoi(argv[1]));
    std::wstring installedJar = argv[2];
    std::wstring newJar = argv[3];
    std::wstring resultFile = argv[4];

    // Wait for the game process to actually exit. Fall back to a poll on
    // process id + parent process name to survive PID reuse while we sleep.
    {
        std::wstring parentName = L"java";
        HANDLE h = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, parentPid);
        if (h) {
            wchar_t buf[MAX_PATH] = {0};
            DWORD len = MAX_PATH;
            if (QueryFullProcessImageNameW(h, 0, buf, &len)) {
                size_t slash = std::wstring(buf).find_last_of(L"\\/");
                parentName = slash == std::wstring::npos ? buf : std::wstring(buf).substr(slash + 1);
            }
            CloseHandle(h);
        }
        auto deadline = std::chrono::steady_clock::now() + std::chrono::minutes(2);
        bool alive = true;
        while (std::chrono::steady_clock::now() < deadline) {
            if (!processExists(parentPid)) { alive = false; break; }
            // re-check that the pid still refers to the same process name
            HANDLE h2 = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, parentPid);
            if (h2) {
                wchar_t buf2[MAX_PATH] = {0};
                DWORD len2 = MAX_PATH;
                bool same = false;
                if (QueryFullProcessImageNameW(h2, 0, buf2, &len2)) {
                    std::wstring name = buf2;
                    size_t slash = name.find_last_of(L"\\/");
                    name = slash == std::wstring::npos ? name : name.substr(slash + 1);
                    same = (name == parentName);
                }
                CloseHandle(h2);
                if (!same) { alive = false; break; }
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(400));
        }
        if (alive) {
            writeResult(resultFile, L"ERR:timeout waiting for game to exit");
            return 3;
        }
    }

    // Fail loudly instead of the old silent "nothing to replace" path: if the
    // installed jar is missing something is badly wrong, and deleting the freshly
    // downloaded jar would lose the only copy of the update.
    if (!fileExists(installedJar)) {
        writeResult(resultFile, L"ERR:installed jar not found; nothing was changed");
        return 5;
    }
    if (!fileExists(newJar)) {
        writeResult(resultFile, L"ERR:downloaded jar (.update) not found; nothing was changed");
        return 6;
    }

    // Retry the swap for up to 60s (Windows Defender / AV can briefly lock files).
    // Between attempts the installed jar is always left in place: each failed try
    // rolls it back from .bak before sleeping, so an interrupted helper never
    // leaves the game without a jar.
    auto deadline2 = std::chrono::steady_clock::now() + std::chrono::seconds(60);
    while (true) {
        std::wstring bak = installedJar + L".bak";
        DeleteFileW(bak.c_str());
        DWORD err = 0;
        if (MoveFileExW(installedJar.c_str(), bak.c_str(), MOVEFILE_REPLACE_EXISTING)) {
            if (MoveFileExW(newJar.c_str(), installedJar.c_str(), MOVEFILE_REPLACE_EXISTING)) {
                DeleteFileW(bak.c_str());
                writeResult(resultFile, L"OK");
                return 0;
            }
            err = GetLastError();
            // New jar could not be placed: restore the installed jar so the game
            // still boots the previous build, then retry until the deadline.
            MoveFileExW(bak.c_str(), installedJar.c_str(), MOVEFILE_REPLACE_EXISTING);
        } else {
            err = GetLastError();
        }
        if (std::chrono::steady_clock::now() >= deadline2) {
            std::wostringstream oss;
            oss << L"ERR:could not replace jar (error " << err << L")";
            writeResult(resultFile, oss.str());
            return 4;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(500));
    }
}
