cmake_minimum_required(VERSION 3.22)
project(astra_wolfcodecs LANGUAGES C CXX)

# Offline, pinned Xiph releases. No network download happens during a build.
set(CMAKE_C_STANDARD 99)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(OGG ${CMAKE_CURRENT_SOURCE_DIR}/vendor/libogg-1.3.6)
set(THEORA ${CMAKE_CURRENT_SOURCE_DIR}/vendor/libtheora-1.2.0)
set(VORBIS ${CMAKE_CURRENT_SOURCE_DIR}/vendor/libvorbis-1.3.7)
file(MAKE_DIRECTORY ${CMAKE_CURRENT_BINARY_DIR}/include/ogg)
file(WRITE ${CMAKE_CURRENT_BINARY_DIR}/include/ogg/config_types.h
  "#pragma once\n#include <stdint.h>\ntypedef int16_t ogg_int16_t;\ntypedef uint16_t ogg_uint16_t;\ntypedef int32_t ogg_int32_t;\ntypedef uint32_t ogg_uint32_t;\ntypedef int64_t ogg_int64_t;\ntypedef uint64_t ogg_uint64_t;\n")

add_library(astra_ogg STATIC ${OGG}/src/bitwise.c ${OGG}/src/framing.c)
target_include_directories(astra_ogg PUBLIC ${OGG}/include ${CMAKE_CURRENT_BINARY_DIR}/include)

set(THEORA_DECODER_SOURCES apiwrapper bitpack decapiwrapper decinfo decode dequant
  fragment huffdec idct info internal quant state)
set(THEORA_FILES)
foreach(source IN LISTS THEORA_DECODER_SOURCES)
  list(APPEND THEORA_FILES ${THEORA}/lib/${source}.c)
endforeach()
add_library(astra_theora STATIC ${THEORA_FILES})
target_include_directories(astra_theora PUBLIC ${THEORA}/include PRIVATE ${THEORA}/lib)
target_link_libraries(astra_theora PUBLIC astra_ogg m)

set(VORBIS_SOURCES mdct smallft block envelope window lsp lpc analysis synthesis psy info
  floor1 floor0 res0 mapping0 registry codebook sharedbook lookup bitrate vorbisfile)
set(VORBIS_FILES)
foreach(source IN LISTS VORBIS_SOURCES)
  list(APPEND VORBIS_FILES ${VORBIS}/lib/${source}.c)
endforeach()
add_library(astra_vorbis STATIC ${VORBIS_FILES})
target_include_directories(astra_vorbis PUBLIC ${VORBIS}/include PRIVATE ${VORBIS}/lib)
target_compile_definitions(astra_vorbis PRIVATE HAVE_ALLOCA_H=1)
target_link_libraries(astra_vorbis PUBLIC astra_ogg m)

add_library(astra_wolfcodecs SHARED wolf_theora.cpp)
target_compile_options(astra_wolfcodecs PRIVATE -Wall -Wextra -Werror=return-type)
target_link_libraries(astra_wolfcodecs PRIVATE astra_theora astra_vorbis)
target_link_options(astra_wolfcodecs PRIVATE -Wl,-z,max-page-size=16384)
