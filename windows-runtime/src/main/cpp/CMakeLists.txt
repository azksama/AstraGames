cmake_minimum_required(VERSION 3.22.1)
project(AstraWindowsRuntime)
add_subdirectory(winlator)
add_subdirectory(gladiorenderer)
add_library(astra_exec_bridge SHARED ../../../../scripts/wolf-runtime/exec_bridge.c)
# Wine WoW64 regresses to an ntdll stack overflow with this interposer built at
# -O2 (Wolf 3.729, API 36.1 x86_64). Keep this small loader shim at -O0 in both
# variants; the X server, renderer and application retain release optimization.
target_compile_options(astra_exec_bridge PRIVATE -O0)
target_link_libraries(astra_exec_bridge dl)
