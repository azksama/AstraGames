These tiny clips are original, generated fixtures, not extracted game assets.

`reference.mp4`: 64×48, 10 fps, 3 seconds, baseline H.264/YUV420P,
red → green → blue in one-second sections; AAC mono 48 kHz, 440 Hz sine.
SHA256: `fd95e44d3c474edc7450a8354fd869812c261699100dd34d2a35d5a7ca1cd3c3`.

`quadrants.mp4`: 64×48, 10 fps, 1 second, baseline H.264/YUV420P,
red/green at top and blue/white at bottom, without audio.
SHA256: `8d0f48e6d211090390f9da897146f0e071f83e3a41cd124cde976d5b87077ecd`.

WolfNativeVideoInstrumentedTest decodes them with the device's real Android
MediaPlayer/MediaCodec, then reads SurfaceTexture/EGL frames into the same
Canvas path as ordinary game pictures. Audio codec metrics and the audio
session verify the real audio path; this does not claim audible physical
speaker verification or compatibility with all MP4 profiles.

`reference.ogv`: the same original 64×48 / 10 fps / three colour sections,
with Theora YUV420P and Vorbis mono 48 kHz / 440 Hz. SHA256:
`fc0e1d432f456a77460a8dc0a3dd2179d9c806dfd58ebd20194c50bb5ea2393e`.

`quadrants.ogv`: Theora version of our silent quadrants fixture. SHA256:
`4b211f972c62f9dc1302df737b2a9f2d7a8c0b54c61a0b67c5ad68dbb0c13181`.

`crop.ogv`: that original quadrant fixture cropped to 50×34 at (6,6). The
Theora coded frame is padded to its block size; the JNI decoder must honour
the picture crop. SHA256:
`68b8de46f8d69031115f7fdd52390dc4b0a1e6933b043f69d643c97565a16382`.

OGV tests call the actual pinned Xiph JNI decoders, verify ARGB frames,
duration, crop and a measured PCM waveform, then verify AudioTrack's real
playback-head clock, pause, speed, seek, looping, gain, Canvas composition
and explicit CRC errors. These do not establish physical speaker playback.

Generation used FFmpeg only as an external fixture-authoring tool, never as
an application dependency. `reference.ogv` was generated using three lavfi
`color=c=red/lime/blue:s=64x48:r=10:d=1` inputs concatenated in that order,
plus `sine=frequency=440:sample_rate=48000:duration=3`, with
`-c:v libtheora -q:v 10 -c:a libvorbis -q:a 5 -f ogg`.
The quadrant fixture used `-i quadrants.mp4 -an -c:v libtheora -q:v 10 -f ogg`;
the crop variant additionally used `-vf crop=50:34:6:6`.
