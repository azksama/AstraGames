"""Build Astra's original, CRT-free Win64 helper with the pinned Android NDK.

The resulting asset runs exclusively through Wine; it has no network imports.
Use --check to verify the checked-in executable matches its source.
"""
from pathlib import Path
import os, subprocess, sys
repo = Path(__file__).resolve().parents[2]
out = repo / 'build/wolf-research/memory-agent'
out.mkdir(parents=True, exist_ok=True)
sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'AppData/Local/Android/Sdk')))
host = 'windows-x86_64' if os.name == 'nt' else 'linux-x86_64'
tools = sdk / 'ndk/29.0.14206865/toolchains/llvm/prebuilt' / host / 'bin'
ext = '.exe' if os.name == 'nt' else ''
fixture = '--fixture' in sys.argv or '--fixture32' in sys.argv
win32 = '--fixture32' in sys.argv
source = repo / ('scripts/wolf-runtime/memory_fixture.c' if fixture else 'scripts/wolf-runtime/memory_agent.c')
import re
symbols = re.findall(r'API\s+(?:\w+\s+|void\s*\*)+(\w+)\(', source.read_text())
if win32:
    params = dict(re.findall(r'API\s+\w+\s+(\w+)\(([^;]*)\);', source.read_text()))
    symbols = [s+'@'+str(0 if params[s]=='void' else len(params[s].split(','))*4) for s in symbols]
definition = out / 'kernel32.def'
definition.write_text('LIBRARY kernel32.dll\nEXPORTS\n'+'\n'.join(symbols)+'\n')
def run(tool, *args):
    subprocess.run([str(tools / (tool+ext)), *map(str,args)],check=True)
run('llvm-dlltool', '-m','i386' if win32 else 'i386:x86-64', *(['-k'] if win32 else []), '-d',definition,'-l',out/'kernel32.lib')
run('clang','--target='+('i686' if win32 else 'x86_64')+'-w64-windows-gnu','-O2','-fno-builtin','-fno-stack-protector','-mno-stack-arg-probe',
    '-Wall','-Wextra','-Werror','-c',source,'-o',out/'agent.obj')
run('ld.lld','-flavor','link','/entry:mainCRTStartup','/subsystem:windows','/timestamp:0',
    out/'agent.obj',out/'kernel32.lib','/out:'+str(out/'astra-memory.exe'))
asset = repo/('build/wolf-research/memory-fixture/Game32.exe' if win32 else 'build/wolf-research/memory-fixture/Game.exe') if fixture else repo/'app/src/main/assets/windows/astra-memory.exe'
if '--check' in sys.argv:
    assert asset.read_bytes() == (out/'astra-memory.exe').read_bytes(), 'Memory agent asset is stale'
else:
    asset.parent.mkdir(parents=True,exist_ok=True)
    asset.write_bytes((out/'astra-memory.exe').read_bytes())
print(asset)
