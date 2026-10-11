"""Build the original x64 Windows volume/save probe using Android NDK LLVM.

ADB-push the produced executable to /data/local/tmp/astra-wine-save-probe.exe;
run WolfWineSaveRuntimeTest with -e wolfWineSaveProbe true on an SDK emulator.
The fixture writes only to the test's disposable game directory.
"""
from pathlib import Path
import os
import subprocess

repo = Path(__file__).resolve().parents[2]
out = repo / "build/wolf-research/wine-save-fixture"
out.mkdir(parents=True, exist_ok=True)
sdk = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "AppData/Local/Android/Sdk")))
tools = sdk / "ndk/29.0.14206865/toolchains/llvm/prebuilt/windows-x86_64/bin"
symbols = ("GetDiskFreeSpaceExA", "GetDiskFreeSpaceA", "GetCurrentDirectoryA", "GetLastError",
           "CreateDirectoryA", "CreateFileA", "WriteFile", "FlushFileBuffers", "CloseHandle", "ExitProcess")
definition = out / "kernel32.def"
definition.write_text("LIBRARY kernel32.dll\nEXPORTS\n" + "\n".join(symbols) + "\n")
def run(*args):
    subprocess.run([str(arg) for arg in args], check=True)
run(tools / "llvm-dlltool.exe", "-m", "i386:x86-64", "-d", definition, "-l", out / "kernel32.lib")
run(tools / "clang.exe", "--target=x86_64-w64-windows-gnu", "-O2", "-fno-builtin", "-c",
    repo / "scripts/wolf-runtime/save_probe.c", "-o", out / "save.obj")
run(tools / "ld.lld.exe", "-flavor", "link", "/entry:mainCRTStartup", "/subsystem:console",
    out / "save.obj", out / "kernel32.lib", "/out:" + str(out / "Game.exe"))
print(out / "Game.exe")
