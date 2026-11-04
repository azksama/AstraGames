#!/usr/bin/env python3
"""Apply the small Astra adapter to the pinned Android fork, before compiling."""
from pathlib import Path
import sys

root = Path(sys.argv[1])
for path in root.rglob('*.sh'):
    path.write_text(path.read_text())
main = root / 'app/jni/mkxp-z/src/main.cpp'
text = main.read_text()
if '\t// Request storage permission (before Android 11)' in text:
    start = text.index('\t// Request storage permission (before Android 11)')
    end = text.index('\t// Set and ensure current directory', start)
    text = text[:start] + '\t// Astra stages each game in its private writable directory; no broad storage permission.\n\n' + text[end:]
main.write_text(text)

# Position-independent static archives are linked into the engine shared library.
makefile = root / 'app/jni/Makefile'
text = makefile.read_text().replace('CFLAGS      := -O0 ', 'CFLAGS      := -O2 -fPIC ')
text = text.replace('-fno-integrated-as ', '')
text = text.replace('NPROC     := $(shell nproc)', 'NPROC     := 6')
if '--disable-arm-a64-neon' not in text:
    text = text.replace('./configure $(CONFIGURE_ARGS) --enable-static=true --enable-shared=false', './configure $(CONFIGURE_ARGS) --enable-static=true --enable-shared=false --disable-arm-a64-neon')
# Autoconf (Ruby) and CMake (OpenAL) do not run through ndk-build, so apply
# both ELF LOAD and RELRO page alignment explicitly to those builds too.
page_flags = '-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384'
text = text.replace('LDFLAGS     := -L$(LIBDIR) -lm -lz', f'LDFLAGS     := {page_flags} -L$(LIBDIR) -lm -lz')
if '-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON' not in text:
    text = text.replace('-DCMAKE_BUILD_TYPE=RelWithDebInfo \\\n', '-DCMAKE_BUILD_TYPE=RelWithDebInfo \\\n\t-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \\\n\t-DCMAKE_SHARED_LINKER_FLAGS="' + page_flags + '" \\\n')
makefile.write_text(text)

# ALooper_pollAll was removed from the current NDK. This is a nonblocking
# per-frame sensor update, so pollOnce processes one pending looper result.
sensor = root / 'app/jni/SDL2/src/sensor/android/SDL_androidsensor.c'
sensor.write_text(sensor.read_text().replace('ALooper_pollAll(', 'ALooper_pollOnce('))

# Match FreeType's void* callback signature rather than casting incompatible
# function pointer types, rejected by HarfBuzz's strict warnings in Clang 21.
harfbuzz = root / 'app/jni/SDL2_ttf/external/harfbuzz/src/hb-ft.cc'
text = harfbuzz.read_text()
for callback in ('hb_ft_face_finalize', '_release_blob'):
    text = text.replace(callback + ' (FT_Face ft_face)\n{', callback + ' (void *data)\n{\n  FT_Face ft_face = static_cast<FT_Face> (data);')
    text = text.replace('(FT_Generic_Finalizer) ' + callback, callback)
harfbuzz.write_text(text)

# Ruby 3.1's x86 SIGSEGV/stack-overflow recovery hardcodes 4096 even though
# normal allocations use the OS page size. Cache sysconf before installing
# handlers; never invoke it from asynchronous signal-handler context.
signal = root / 'app/jni/ruby/signal.c'
text = signal.read_text()
if 'astra_signal_page_size' not in text:
    old = '# ifdef USE_UCONTEXT_REG\nstatic void\ncheck_stack_overflow'
    assert old in text
    text = text.replace(old, '# ifdef USE_UCONTEXT_REG\nstatic uintptr_t astra_signal_page_size;\nstatic void\ncheck_stack_overflow')
    assert 'enum {pagesize = 4096};' in text
    text = text.replace('enum {pagesize = 4096};', 'const uintptr_t pagesize = astra_signal_page_size;')
    old = 'Init_signal(void)\n{'
    assert old in text
    text = text.replace(old, '''Init_signal(void)
{
#ifdef USE_UCONTEXT_REG
    long system_page_size = sysconf(_SC_PAGESIZE);
    if (system_page_size <= 0) rb_bug("Cannot determine system page size");
    astra_signal_page_size = (uintptr_t)system_page_size;
#endif''')
    signal.write_text(text)

# The RGSS SDL pthread is attached to ART before Ruby starts. Its fixed Bionic
# stack can contain JVM protection beyond what pthread_getattr_np reports.
# Linux main-stack prefaulting with a near-full-stack alloca crosses that guard
# on 16 KiB devices (observed SIGSEGV at reserve_stack's limit[0] write).
# Android already maps pthread stacks: preserve Ruby's measured stack bounds
# and overflow checks, but do not prefault the entire pthread stack.
pthread = root / 'app/jni/ruby/thread_pthread.c'
text = pthread.read_text()
text = text.replace('#ifdef __linux__\nstatic __attribute__((noinline)) void\nreserve_stack', '#if defined(__linux__) && !defined(__ANDROID__)\nstatic __attribute__((noinline)) void\nreserve_stack')
pthread.write_text(text)
