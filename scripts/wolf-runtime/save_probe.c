/* Original Win32 API fixture. No game code or assets.
 * Run inside the SDK emulator's Wine prefix, never on a user's real game. */
typedef unsigned long DWORD;
typedef unsigned long long U64;
typedef void *HANDLE;
#define IMPORT __declspec(dllimport)
IMPORT int GetDiskFreeSpaceExA(const char *, U64 *, U64 *, U64 *);
IMPORT int GetDiskFreeSpaceA(const char *, DWORD *, DWORD *, DWORD *, DWORD *);
IMPORT DWORD GetCurrentDirectoryA(DWORD, char *);
IMPORT DWORD GetLastError(void);
IMPORT int CreateDirectoryA(const char *, void *);
IMPORT HANDLE CreateFileA(const char *, DWORD, DWORD, void *, DWORD, DWORD, HANDLE);
IMPORT int WriteFile(HANDLE, const void *, DWORD, DWORD *, void *);
IMPORT int FlushFileBuffers(HANDLE);
IMPORT int CloseHandle(HANDLE);
IMPORT void ExitProcess(unsigned int);

static HANDLE output;
static void line(const char *text) {
    DWORD size = 0, written;
    while (text[size]) size++;
    WriteFile(output, text, size, &written, 0);
}
static void number(const char *key, U64 value) {
    char text[32]; unsigned int at = 31;
    text[at] = 0;
    do { text[--at] = '0' + value % 10; value /= 10; } while (value);
    line(key); line("="); line(text + at); line("\n");
}
static U64 disk(const char *key, const char *path) {
    U64 available = 0, total = 0, allFree = 0;
    int ok = GetDiskFreeSpaceExA(path, &available, &total, &allFree);
    number(key, ok ? available : 0);
    return ok ? available : 0;
}
void mainCRTStartup(void) {
    char current[1024], root[4];
    GetCurrentDirectoryA(sizeof(current), current);
    root[0] = current[0]; root[1] = ':'; root[2] = '\\'; root[3] = 0;
    output = CreateFileA("astra-wine-save-probe.txt", 0x40000000, 3, 0, 2, 0x80, 0);
    if (output == (HANDLE)-1) ExitProcess(10);
    line("current="); line(current); line("\n");
    disk("directory_available", ".");
    disk("z_available", "Z:\\");
    U64 currentFree = disk("current_available", 0);
    U64 rootFree = disk("root_available", root);
    DWORD sectors = 0, bytes = 0, freeClusters = 0, clusters = 0;
    int legacy = GetDiskFreeSpaceA(root, &sectors, &bytes, &freeClusters, &clusters);
    number("legacy_available", legacy ? (U64)sectors * bytes * freeClusters : 0);
    int created = CreateDirectoryA("SaveData", 0);
    DWORD error = created ? 0 : GetLastError();
    number("mkdir_error", error);
    HANDLE save = CreateFileA("SaveData/AstraProbe.sav", 0x40000000, 3, 0, 2, 0x80, 0);
    int writtenOk = 0;
    if (save != (HANDLE)-1) {
        const char *payload = "Astra Wine save probe";
        DWORD written = 0;
        writtenOk = WriteFile(save, payload, 21, &written, 0) && written == 21 && FlushFileBuffers(save);
        CloseHandle(save);
    }
    number("save_written", writtenOk);
    FlushFileBuffers(output); CloseHandle(output);
    ExitProcess(!writtenOk ? 30 : (!currentFree || !rootFree || !freeClusters ? 20 : 0));
}
