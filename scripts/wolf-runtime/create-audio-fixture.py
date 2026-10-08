"""Build an original Windows PlaySound fixture using the Android NDK LLVM tools.

Copy Game.exe and tone.wav into the debug app's files/wolf-probe/audio directory;
run WolfAudioRuntimeTest with -e wolfAudio true. No game assets are required.
"""
from pathlib import Path
import math
import os
import struct
import subprocess
import wave

repo = Path(__file__).resolve().parents[2]
root = repo / "build/wolf-research/audio-fixture"
root.mkdir(parents=True, exist_ok=True)
sdk = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "AppData/Local/Android/Sdk")))
tools = sdk / "ndk/29.0.14206865/toolchains/llvm/prebuilt/windows-x86_64/bin"
(root / "audio.c").write_text('''__declspec(dllimport) int PlaySoundA(const char*,void*,unsigned int);
__declspec(dllimport) void Sleep(unsigned long);
__declspec(dllimport) void ExitProcess(unsigned int);
void mainCRTStartup(void) { int ok=PlaySoundA("tone.wav",0,0x20009); Sleep(30000); ExitProcess(ok?0:1); }
''')
def run(*args):
    subprocess.run([str(arg) for arg in args], check=True)
for dll, symbols in [("winmm", "PlaySoundA"), ("kernel32", "Sleep\nExitProcess")]:
    (root / (dll + ".def")).write_text("LIBRARY " + dll + ".dll\nEXPORTS\n" + symbols + "\n")
    run(tools / "llvm-dlltool.exe", "-m", "i386:x86-64", "-d", root / (dll + ".def"), "-l", root / (dll + ".lib"))
run(tools / "clang.exe", "--target=x86_64-w64-windows-gnu", "-c", root / "audio.c", "-o", root / "audio.obj")
run(tools / "ld.lld.exe", "-flavor", "link", "/entry:mainCRTStartup", "/subsystem:windows",
    root / "audio.obj", root / "winmm.lib", root / "kernel32.lib", "/out:" + str(root / "Game.exe"))
with wave.open(str(root / "tone.wav"), "w") as wav:
    wav.setparams((1, 2, 22050, 0, "NONE", "not compressed"))
    wav.writeframes(b"".join(struct.pack("<h", int(2500 * math.sin(2 * math.pi * 440 * i / 22050))) for i in range(22050)))
print(root)
