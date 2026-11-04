"""Generate original CC0 RGSS scripts archives and PCM audio; no RPG Maker/RTP assets."""
from pathlib import Path
import math
import struct
import wave
import zlib

ROOT = Path(__file__).resolve().parents[2]
DEST = ROOT / "app/src/androidTest/assets/mkxpz"

def integer(n):
    if n == 0:
        return b"\0"
    if 0 < n < 123:
        return bytes([n + 5])
    raw = n.to_bytes((n.bit_length() + 7) // 8, "little")
    return bytes([len(raw)]) + raw

def marshal(value):
    if isinstance(value, int):
        return b"i" + integer(value)
    if isinstance(value, bytes):
        return b'"' + integer(len(value)) + value
    return b"[" + integer(len(value)) + b"".join(marshal(item) for item in value)

SCRIPT = r'''
VERSION = %VERSION%
require 'fileutils'
# Exercise native page-sensitive paths, including mmap/munmap, fiber stack guards
# and GC compaction's mprotect. IO::Buffer reads the page size inside the Ruby VM.
NATIVE_PAGE_SIZE = IO::Buffer::PAGE_SIZE
retained = Array.new(4096) { |i| [i, "retained-#{i}" * 16] }
gc_moved = 0
3.times do
  4096.times { |i| ("temporary-#{i}" * 64).reverse }
  GC.start
  gc_moved += GC.compact.fetch(:moved).values.sum
  raise "GC corrupted retained objects" unless retained.each_with_index.all? { |item, i| item == [i, "retained-#{i}" * 16] }
end
fibers = Array.new(32) { Fiber.new { |v| Fiber.yield(v + 1); v + 2 } }
fibers.each_with_index { |fiber, i| raise "Fiber yield failed" unless fiber.resume(i) == i + 1 }
fibers.each_with_index { |fiber, i| raise "Fiber resume failed" unless fiber.resume == i + 2 }
threads = Array.new(4) { |i| Thread.new { [i] * 1000 } }
threads.each_with_index { |thread, i| raise "Thread join failed" unless thread.value == [i] * 1000 }
mapped = IO::Buffer.new(NATIVE_PAGE_SIZE * 2, IO::Buffer::MAPPED)
begin
  mapped.set_value(:U8, 0, 37)
  mapped.set_value(:U8, mapped.size - 1, 91)
  raise "Mapped page data corrupted" unless mapped.get_value(:U8, 0) == 37 && mapped.get_value(:U8, mapped.size - 1) == 91
ensure
  mapped.free
end
GC.start
SAVE = "Save01.%EXT%"
expected_size = VERSION == 1 ? [640, 480] : [544, 416]
raise "Wrong native RGSS dimensions" unless [Graphics.width, Graphics.height] == expected_size
raise "Wrong RGSS3 API" unless (defined?(RGSS_VERSION) != nil) == (VERSION == 3)
raise "Wrong RGSS2 API" unless Window.method_defined?(:openness) == (VERSION >= 2)
count = File.exist?(SAVE) ? File.open(SAVE, "rb") { |f| Marshal.load(f) } : 0
event = File.exist?(SAVE) ? "loaded" : "ready"
raise "Marshal value invalid" unless count.is_a?(Integer)
Graphics.frame_rate = 60
table = Table.new(2, 2, 2)
table[1,1,1] = 37
raise "Table binding failed" unless table[1,1,1] == 37
bitmap = Bitmap.new(Graphics.width, Graphics.height)
sprite = Sprite.new
sprite.bitmap = bitmap
bitmap.font.size = 22
audio = "not_started"
begin
  Audio.bgm_play("Audio/BGM/probe.wav", 65, 100)
  audio = "bgm_requested"
rescue => error
  audio = error.to_s.gsub(/[^a-zA-Z0-9 _-]/, "_")
end
write_state = lambda do
  File.open("astra-proof.json.tmp", "wb") do |f|
    f.write('{"rgss":' + VERSION.to_s + ',"count":' + count.to_s + ',"event":"' + event + '","audio":"' + audio + '","frame":' + Graphics.frame_count.to_s + ',"nativePageSize":' + NATIVE_PAGE_SIZE.to_s + ',"gcMoved":' + gc_moved.to_s + ',"memoryProbe":"passed"}')
    f.flush
    f.fsync
  end
  File.rename("astra-proof.json.tmp", "astra-proof.json")
end
dirty = true
loop do
  Input.update
  if Input.trigger?(Input::RIGHT)
    count += 1
    event = "right"
    dirty = true
  end
  if Input.trigger?(Input::LEFT)
    count -= 1
    event = "left"
    dirty = true
  end
  if Input.trigger?(Input::C)
    File.open(SAVE + ".tmp", "wb") { |f| Marshal.dump(count, f); f.flush; f.fsync }
    File.rename(SAVE + ".tmp", SAVE)
    Audio.se_play("Audio/SE/probe.wav", 80, 100)
    event = "saved"
    dirty = true
  end
  if Input.trigger?(Input::B)
    event = "exit"
    write_state.call
    break
  end
  if dirty
    bitmap.fill_rect(0, 0, 640, 480, Color.new(18, 27, 47))
    bitmap.fill_rect(24, 24, 592, 8, Color.new(80, 220, 175))
    bitmap.draw_text(24, 52, 592, 42, "ASTRA - MKXP-Z / RGSS " + VERSION.to_s)
    bitmap.draw_text(24, 125, 592, 42, "State: " + count.to_s + "   /   " + event)
    bitmap.draw_text(24, 195, 592, 42, "LEFT / RIGHT: change state")
    bitmap.draw_text(24, 245, 592, 42, "A / ENTER: save + sound")
    bitmap.draw_text(24, 295, 592, 42, "B / ESC: exit; relaunch to load")
    bitmap.fill_rect(30 + (count % 12) * 44, 375, 34, 34, Color.new(80, 220, 175))
    dirty = false
    3.times { Graphics.update }
    write_state.call
  end
  Graphics.update
end
Audio.bgm_stop
sprite.dispose
bitmap.dispose
'''

for version, name, extension, dll in [(1, "xp", "rxdata", "RGSS104E.dll"),
                                      (2, "vx", "rvdata", "RGSS202E.dll"),
                                      (3, "ace", "rvdata2", "RGSS301.dll")]:
    folder = DEST / name
    (folder / "Data").mkdir(parents=True, exist_ok=True)
    code = SCRIPT.replace("%VERSION%", str(version)).replace("%EXT%", extension).encode()
    archive = b"\x04\x08" + marshal([[1, b"Astra original RGSS probe", zlib.compress(code)]])
    (folder / "Data" / ("Scripts." + extension)).write_bytes(archive)
    (folder / "Game.ini").write_text(f"[Game]\nLibrary={dll}\nScripts=Data/Scripts.{extension}\nTitle=Astra RGSS {version} fixture\nRTP=\n", encoding="ascii")
    for kind in ["BGM", "SE"]:
        audio = folder / "Audio" / kind / "probe.wav"
        audio.parent.mkdir(parents=True, exist_ok=True)
        with wave.open(str(audio), "wb") as output:
            output.setparams((1, 2, 22050, 0, "NONE", "not compressed"))
            output.writeframes(b"".join(struct.pack("<h", int(6000 * math.sin(2 * math.pi * 440 * i / 22050)))
                                      for i in range(22050 if kind == "BGM" else 5512)))
print(DEST)
