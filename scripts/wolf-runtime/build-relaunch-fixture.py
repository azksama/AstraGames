"""Build the authored Win64 launch/child-liveness fixture; SDK emulator tests only."""
from pathlib import Path
import os, subprocess
repo = Path(__file__).resolve().parents[2]
out = repo / 'build/wolf-research/relaunch-fixture'
out.mkdir(parents=True, exist_ok=True)
sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'AppData/Local/Android/Sdk')))
host = 'windows-x86_64' if os.name == 'nt' else 'linux-x86_64'
tools = sdk / 'ndk/29.0.14206865/toolchains/llvm/prebuilt' / host / 'bin'
ext = '.exe' if os.name == 'nt' else ''
source = repo / 'scripts/wolf-runtime/relaunch_fixture.c'
# Explicit list keeps pointer-return imports unambiguous for this small fixture.
symbols = ['GetCommandLineW','GetModuleFileNameW','CreateProcessW','CreateFileA','GetFileAttributesA','WriteFile','CloseHandle','Sleep','ExitProcess']
definition = out / 'kernel32.def'
definition.write_text('LIBRARY kernel32.dll\nEXPORTS\n' + '\n'.join(symbols) + '\n')
def run(tool, *args):
    subprocess.run([str(tools / (tool + ext)), *map(str,args)],check=True)
run('llvm-dlltool','-m','i386:x86-64','-d',definition,'-l',out/'kernel32.lib')
run('clang','--target=x86_64-w64-windows-gnu','-O2','-fno-builtin','-fno-stack-protector','-mno-stack-arg-probe',
    '-Wall','-Wextra','-Werror','-c',source,'-o',out/'fixture.obj')
run('ld.lld','-flavor','link','/entry:mainCRTStartup','/subsystem:windows','/timestamp:0',out/'fixture.obj',out/'kernel32.lib','/out:'+str(out/'Game.exe'))
print(out/'Game.exe')
