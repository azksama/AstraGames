# Pinned native Wolf video codecs

These are unchanged `include`, `src`/`lib`, `COPYING` and `AUTHORS` files from the
official Xiph release archives. The application builds the decoder sources
offline. No GPL component or FFmpeg library is linked into the application.

| Package | Official archive | SHA-256 |
| --- | --- | --- |
| libogg 1.3.6 | https://downloads.xiph.org/releases/ogg/libogg-1.3.6.tar.gz | 83e6704730683d004d20e21b8f7f55dcb3383cdf84c0daedf30bde175f774638 |
| libtheora 1.2.0 | https://downloads.xiph.org/releases/theora/libtheora-1.2.0.tar.gz | 279327339903b544c28a92aeada7d0dcfd0397b59c2f368cc698ac56f515906e |
| libvorbis 1.3.7 | https://downloads.xiph.org/releases/vorbis/libvorbis-1.3.7.tar.gz | 0e982409a9c3fc82ee06e08205b1355e5c6aa4c36bca58146ef399621b0ce5ab |

The archive hashes were checked against https://xiph.org/downloads/. Each
package retains its BSD-style license in `COPYING`. Theora uses the generic
decoder on arm64-v8a and x86_64, without assuming optional CPU instruction sets.
The JNI wrapper preserves the picture crop and all three supported chroma
subsampling formats (4:2:0, 4:2:2 and 4:4:4), and emits PCM16LE for mono/stereo
Vorbis. Chained streams and other Ogg codecs report an explicit error.
