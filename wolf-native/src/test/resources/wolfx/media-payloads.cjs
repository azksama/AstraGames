// Our small PNG/WAV fixture payloads, independent of Android's premultiplied
// encoder. The C++ generator wraps these using the pinned WOLFX oracle.
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const out = path.resolve(process.argv[2] || 'build/wolf-research/wolfx-media-payloads');
fs.mkdirSync(out, {recursive:true});
function crc32(bytes) {
  let crc = 0xffffffff;
  for (const byte of bytes) {
    crc ^= byte;
    for (let i = 0; i < 8; i++) crc = (crc >>> 1) ^ (-(crc & 1) & 0xedb88320);
  }
  return (crc ^ 0xffffffff) >>> 0;
}
function chunk(name, bytes) {
  const type = Buffer.from(name);
  const result = Buffer.alloc(12 + bytes.length);
  result.writeUInt32BE(bytes.length, 0); type.copy(result, 4); bytes.copy(result, 8);
  result.writeUInt32BE(crc32(Buffer.concat([type, bytes])), 8 + bytes.length);
  return result;
}
const width = 16, height = 16;
const image = Buffer.alloc(height * (1 + width * 4));
for (let y = 0; y < height; y++) for (let x = 0; x < width; x++) {
  const color = x < 8 ? (y < 8 ? [220,40,90,0] : [40,160,220,255])
                      : (y < 8 ? [20,180,80,128] : [220,190,10,255]);
  Buffer.from(color).copy(image, y * (1 + width * 4) + 1 + x * 4);
}
const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(width,0); ihdr.writeUInt32BE(height,4);
ihdr[8] = 8; ihdr[9] = 6;
fs.writeFileSync(path.join(out, 'quadrants.png'), Buffer.concat([
  Buffer.from([137,80,78,71,13,10,26,10]), chunk('IHDR', ihdr),
  chunk('IDAT', zlib.deflateSync(image)), chunk('IEND', Buffer.alloc(0))]));
const rate = 48000, frames = rate * 3, pcm = Buffer.alloc(frames * 2);
for (let i = 0; i < frames; i++) pcm.writeInt16LE(Math.round(12000 * Math.sin(2 * Math.PI * 440 * i / rate)), i * 2);
const wave = Buffer.alloc(44); wave.write('RIFF',0); wave.writeUInt32LE(36 + pcm.length,4);
wave.write('WAVEfmt ',8); wave.writeUInt32LE(16,16); wave.writeUInt16LE(1,20);
wave.writeUInt16LE(1,22); wave.writeUInt32LE(rate,24); wave.writeUInt32LE(rate*2,28);
wave.writeUInt16LE(2,32); wave.writeUInt16LE(16,34); wave.write('data',36);
wave.writeUInt32LE(pcm.length,40);
fs.writeFileSync(path.join(out, 'sine.wav'), Buffer.concat([wave,pcm]));
process.stdout.write('Authored RGBA16x16 PNG and PCM48kHz mono3s WAV ready\n');
