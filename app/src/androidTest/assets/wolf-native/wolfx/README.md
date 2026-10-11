# Authored WOLFX media fixtures

These files belong only to the instrumentation test APK. They are authored
fixtures, not encrypted pictures or movies extracted from a commercial game.
The five real private WOLFX fonts are a separate corpus and remain outside Git.

`quadrants.png` is our 16×16 RGBA PNG. Its four regions contain RGB
220/40/90 with alpha0, 20/180/80 with alpha128, 40/160/220 with alpha255,
and 220/190/10 with alpha255. `sine.wav` is our three-second PCM16 mono
48kHz/440Hz waveform. The MP4 and OGV plaintexts are the original
`../video/reference.mp4` and `../video/reference.ogv` fixtures documented there.

The protected payloads use the format generator in
`wolf-native/src/test/resources/wolfx/media-reference.cpp`, which reuses the
independent C++ oracle and verifies every result with `decryptFull` from
UberWolf commit `f4191375d289171b1f29dbc5ca320133b2b4b96a`. The generator
requires `WOLFX_UPSTREAM`; it cannot label an unverified build as passing.
No original Game.exe is executed. The default envelope uses an empty key,
disabled string/numeric lookups (65535/16777215) and seed `0x27b89c04`.
Negative fixtures need string index0 (`required`), numeric index42 (7), or
folder key `private`. The host supplies none of those keys and must report
the failure without falling back to a coexisting plaintext file.

Reproduction from the repository root, using Node and the VS18 BuildTools
AMD64 developer shell (not the x86 target, whose pinned upstream headers
have an unmodified `uintmax_t` narrowing compilation error):

```powershell
node wolf-native/src/test/resources/wolfx/media-payloads.cjs build/wolf-research/wolfx-media-payloads
cl /std:c++20 /EHsc /O2 /D WOLFX_UPSTREAM /I build/wolf-rea/upstream/UberWolf/UberWolfLib/WolfX/detail /Fe:build/wolf-research/wolfx-media-reference.exe /Fo:build/wolf-research/wolfx-media-reference.obj wolf-native/src/test/resources/wolfx/media-reference.cpp
build/wolf-research/wolfx-media-reference.exe build/wolf-research/wolfx-media-fixtures build/wolf-research/wolfx-media-payloads/quadrants.png build/wolf-research/wolfx-media-payloads/sine.wav app/src/androidTest/assets/wolf-native/video/reference.mp4 app/src/androidTest/assets/wolf-native/video/reference.ogv
```

The existing native run compared all16 media/envelope combinations exactly
with the pinned upstream. Its private report is
`build/wolf-research/wolfx-media-native-proof.tsv`, SHA256
`e26c3cc165c307d6fecbd207527945416cf327e99361a6f5c6df8652f1f248b1`.
This establishes bytes, not Android playback. The following SDK methods
are a separate validation stage:

- ResourceBytes: alias priority, concurrent decode, bounded cache, missing
  key/header errors, UI-thread guard, close cancellation, real sound duration.
- Rendering: actual image pixels, opaque RGB, image filters, the distinct
  red/blue mask channels, and a shared protected payload read.
- Audio: actual Android players, gain, pause/resume and plaintext cache files.
- Video: actual AVC/AAC or Theora/Vorbis decoding and audio sessions, timeline,
  pause, seek, gain and decoded colour frames for implicit/explicit names.

No claim is made here for physical speaker audibility, arbitrary game video
profiles, unavailable Pro keys, or protected map/text/CSV readers. The nine
dedicated Android methods pass on the SDK emulator (four reader, two rendering,
one audio and two video tests), within the 117 media checks. The full batch and
its separate game journeys are recorded in `docs/VALIDATION_1.12.0-beta.15.md`.

| File | Bytes | SHA256 |
|---|---:|---|
|quadrants.png|101|33a1cfeb72fb2a2aa0f7036839a806995e58955b05ec4ca3abe93486d59ce17b|
|quadrants.png.wolfx|953|d6df9779dcb5ce7c3c22b907b10a03b77a9ae710094717727be471137d5b02dd|
|quadrants.png.string-key.wolfx|953|8416b417bf058f9d061844329809a29b708ab370bc183c2a2f47531fde0dadbb|
|quadrants.png.number-key.wolfx|953|1776c779c1606f45cc15e6e5d87db2f4e883c61636f24dd28eb53c49b689cc46|
|quadrants.png.folder-key.wolfx|720|8602c03c41182439ba609c7e9bbbec742ca114005a5a72b3dbf5acd578ce1de2|
|sine.wav|288044|99df022afce64717be038c54c71bddaab8e60b071995812bdc162bcebac111bc|
|sine.wav.wolfx|288896|581f12e790a9d951c1da25ff39045c2bcd0c6094e62058e122f01286672fb625|
|reference.mp4.wolfx|28835|941d87a99be1062c195ffae3570771c76e2d6e02b40abdcc9036eff40fe7a1a1|
|reference.ogv.wolfx|15845|00e2148f211c951ae08530486b3b98ca9a09cae2ff286d5d6ff8f1a013e062b7|
