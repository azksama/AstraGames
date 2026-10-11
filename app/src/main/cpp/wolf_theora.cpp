#include <jni.h>
#include <ogg/ogg.h>
#include <theora/theoradec.h>
#include <vorbis/vorbisfile.h>
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <cstdint>
#include <cstdio>
#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

namespace {
constexpr size_t MAX_FILE = 128u * 1024u * 1024u;
constexpr int64_t MAX_PIXELS = 4194304;
void require(bool condition, const char* detail) {
  if (!condition) throw std::runtime_error(detail);
}

// A reader owns its ogg_sync_state; video, probing and Vorbis use independent
// cursors on one immutable byte vector. No JNI array is pinned while decoding.
class Pages {
 public:
  explicit Pages(const std::vector<uint8_t>& data) : bytes(data) { ogg_sync_init(&sync); }
  ~Pages() { ogg_sync_clear(&sync); }
  bool next(ogg_page& page) {
    for (;;) {
      const int result = ogg_sync_pageout(&sync, &page);
      require(result >= 0, "page Ogg corrompue ou somme CRC incorrecte");
      if (result == 1) return true;
      if (cursor == bytes.size()) {
        require(sync.fill == sync.returned, "dernière page Ogg tronquée");
        return false;
      }
      const size_t count = std::min<size_t>(16384, bytes.size() - cursor);
      char* buffer = ogg_sync_buffer(&sync, static_cast<long>(count));
      require(buffer != nullptr, "allocation du démultiplexeur Ogg impossible");
      std::memcpy(buffer, bytes.data() + cursor, count);
      require(ogg_sync_wrote(&sync, static_cast<long>(count)) == 0, "écriture Ogg impossible");
      cursor += count;
    }
  }
 private:
  const std::vector<uint8_t>& bytes;
  ogg_sync_state sync{};
  size_t cursor = 0;
};

struct Stream {
  ogg_stream_state value{};
  explicit Stream(int serial) { require(ogg_stream_init(&value, serial) == 0, "initialisation du flux Ogg impossible"); }
  ~Stream() { ogg_stream_clear(&value); }
};

class TheoraFrames {
 public:
  TheoraFrames(const std::vector<uint8_t>& bytes, int serial) : data(bytes), serial(serial) {
    th_info_init(&info); th_comment_init(&comment);
    try { reset(); } catch (...) { clear(); th_comment_clear(&comment); th_info_clear(&info); throw; }
  }
  ~TheoraFrames() { clear(); th_comment_clear(&comment); th_info_clear(&info); }
  const th_info& format() const { return info; }
  double granuleTime(ogg_int64_t granule) const { return th_granule_time(decoder, granule); }
  int64_t frameAt(int64_t milliseconds, std::vector<jint>& colors) {
    const int64_t target = static_cast<int64_t>(std::floor(milliseconds *
      static_cast<double>(info.fps_numerator) / (1000.0 * info.fps_denominator)));
    if (target < frame) reset();
    while (frame < target) {
      ogg_packet packet{};
      if (!nextPacket(packet)) break;
      ogg_int64_t granule = -1;
      const int result = th_decode_packetin(decoder, &packet, &granule);
      require(result == 0 || result == TH_DUPFRAME, "image Theora corrompue");
      ++frame;
    }
    require(frame >= 0, "vidéo Theora sans image décodable");
    if (published != frame) {
      th_ycbcr_buffer planes;
      require(th_decode_ycbcr_out(decoder, planes) == 0, "image YCbCr Theora indisponible");
      convert(planes, colors);
      published = frame;
    }
    return frame;
  }
 private:
  const std::vector<uint8_t>& data;
  int serial;
  th_info info{};
  th_comment comment{};
  th_setup_info* setup = nullptr;
  th_dec_ctx* decoder = nullptr;
  std::unique_ptr<Pages> pages;
  std::unique_ptr<Stream> stream;
  int64_t frame = -1;
  int64_t published = -2;
  void clear() {
    if (decoder) th_decode_free(decoder);
    decoder = nullptr;
    if (setup) th_setup_free(setup);
    setup = nullptr;
    stream.reset(); pages.reset();
  }
  bool nextPacket(ogg_packet& packet) {
    for (;;) {
      const int result = ogg_stream_packetout(&stream->value, &packet);
      require(result >= 0, "paquet Theora manquant");
      if (result == 1) return true;
      ogg_page page{};
      if (!pages->next(page)) return false;
      if (ogg_page_serialno(&page) == serial)
        require(ogg_stream_pagein(&stream->value, &page) == 0, "page Theora invalide");
    }
  }
  void reset() {
    clear();
    th_comment_clear(&comment); th_comment_init(&comment);
    th_info_clear(&info); th_info_init(&info);
    pages = std::make_unique<Pages>(data);
    stream = std::make_unique<Stream>(serial);
    for (int header = 0; header < 3; ++header) {
      ogg_packet packet{};
      require(nextPacket(packet), "en-tête Theora tronqué");
      require(th_decode_headerin(&info, &comment, &setup, &packet) > 0, "en-tête Theora invalide");
    }
    require(info.pic_width > 0 && info.pic_height > 0 && info.pic_width <= 8192 && info.pic_height <= 8192 &&
      static_cast<int64_t>(info.pic_width) * info.pic_height <= MAX_PIXELS,
      "vidéo Theora dépassant 4194304 pixels ou dimensions invalides");
    require(info.frame_width > 0 && info.frame_height > 0 && info.frame_width <= 8192 && info.frame_height <= 8192 &&
      static_cast<int64_t>(info.frame_width) * info.frame_height <= MAX_PIXELS,
      "cadre codé Theora dépassant 4194304 pixels ou dimensions invalides");
    require(info.fps_numerator > 0 && info.fps_denominator > 0, "fréquence Theora invalide");
    require(info.pixel_fmt == TH_PF_420 || info.pixel_fmt == TH_PF_422 || info.pixel_fmt == TH_PF_444,
      "format de chrominance Theora non pris en charge");
    decoder = th_decode_alloc(&info, setup);
    require(decoder != nullptr, "allocation du décodeur Theora impossible");
    th_setup_free(setup); setup = nullptr;
    frame = -1; published = -2;
  }
  void convert(const th_ycbcr_buffer planes, std::vector<jint>& colors) const {
    const int width = static_cast<int>(info.pic_width), height = static_cast<int>(info.pic_height);
    colors.resize(static_cast<size_t>(width) * height);
    const int shiftX = info.pixel_fmt == TH_PF_444 ? 0 : 1;
    const int shiftY = info.pixel_fmt == TH_PF_420 ? 1 : 0;
    for (int y = 0; y < height; ++y) {
      const int row = y + static_cast<int>(info.pic_y);
      const unsigned char* luma = planes[0].data + row * planes[0].stride;
      const unsigned char* cb = planes[1].data + (row >> shiftY) * planes[1].stride;
      const unsigned char* cr = planes[2].data + (row >> shiftY) * planes[2].stride;
      for (int x = 0; x < width; ++x) {
        const int column = x + static_cast<int>(info.pic_x);
        const int Y = luma[column] - 16, U = cb[column >> shiftX] - 128, V = cr[column >> shiftX] - 128;
        const int red = std::clamp((298 * Y + 409 * V + 128) >> 8, 0, 255);
        const int green = std::clamp((298 * Y - 100 * U - 208 * V + 128) >> 8, 0, 255);
        const int blue = std::clamp((298 * Y + 516 * U + 128) >> 8, 0, 255);
        colors[static_cast<size_t>(y) * width + x] = static_cast<jint>(0xff000000u |
          (static_cast<uint32_t>(red) << 16) | (static_cast<uint32_t>(green) << 8) | static_cast<uint32_t>(blue));
      }
    }
  }
};

struct MemoryCursor { const std::vector<uint8_t>* bytes; size_t position = 0; };
size_t memoryRead(void* output, size_t size, size_t count, void* pointer) {
  auto& cursor = *static_cast<MemoryCursor*>(pointer);
  if (size == 0) return 0;
  const size_t available = (cursor.bytes->size() - cursor.position) / size;
  const size_t result = std::min(count, available);
  std::memcpy(output, cursor.bytes->data() + cursor.position, result * size);
  cursor.position += result * size;
  return result;
}
int memorySeek(void* pointer, ogg_int64_t offset, int whence) {
  auto& cursor = *static_cast<MemoryCursor*>(pointer);
  const int64_t base = whence == SEEK_SET ? 0 : whence == SEEK_CUR ? static_cast<int64_t>(cursor.position) :
    whence == SEEK_END ? static_cast<int64_t>(cursor.bytes->size()) : -1;
  if (base < 0 || offset < -base || offset > static_cast<int64_t>(cursor.bytes->size()) - base) return -1;
  cursor.position = static_cast<size_t>(base + offset);
  return 0;
}
long memoryTell(void* pointer) { return static_cast<long>(static_cast<MemoryCursor*>(pointer)->position); }

class Movie {
 public:
  explicit Movie(std::vector<uint8_t> bytes) : bytes(std::move(bytes)), audioCursor{&this->bytes, 0} {
    int videoSerial = 0, audioSerial = 0;
    bool hasVideo = false, hasAudio = false;
    ogg_int64_t lastVideoGranule = -1;
    Pages pages(this->bytes); ogg_page page{};
    while (pages.next(page)) {
      if (ogg_page_bos(&page)) {
        Stream stream(ogg_page_serialno(&page)); ogg_packet packet{};
        require(ogg_stream_pagein(&stream.value, &page) == 0 && ogg_stream_packetout(&stream.value, &packet) == 1,
          "identification du codec Ogg incomplète");
        const bool theora = packet.bytes >= 7 && packet.packet[0] == 0x80 && std::memcmp(packet.packet + 1, "theora", 6) == 0;
        const bool vorbis = packet.bytes >= 7 && packet.packet[0] == 1 && std::memcmp(packet.packet + 1, "vorbis", 6) == 0;
        const bool skeleton = packet.bytes >= 8 && std::memcmp(packet.packet, "fishead\0", 8) == 0;
        if (theora) {
          require(!hasVideo, "plusieurs flux Theora ou vidéo Ogg chaînée non prise en charge");
          hasVideo = true; videoSerial = ogg_page_serialno(&page);
        } else if (vorbis) {
          require(!hasAudio, "plusieurs flux Vorbis ou audio Ogg chaîné non pris en charge");
          hasAudio = true; audioSerial = ogg_page_serialno(&page);
        } else require(skeleton, "codec Ogg absent : seules les pistes Theora et Vorbis sont prises en charge");
      }
      if (hasVideo && ogg_page_serialno(&page) == videoSerial && ogg_page_granulepos(&page) >= 0)
        lastVideoGranule = ogg_page_granulepos(&page);
    }
    (void)audioSerial;
    require(hasVideo && lastVideoGranule >= 0, "OGV sans vidéo Theora complète");
    video = std::make_unique<TheoraFrames>(this->bytes, videoSerial);
    const auto& format = video->format();
    width = static_cast<int>(format.pic_width); height = static_cast<int>(format.pic_height);
    durationMs = static_cast<int64_t>(std::llround(video->granuleTime(lastVideoGranule) * 1000.0));
    require(durationMs > 0, "durée Theora invalide");
    if (hasAudio) {
      const ov_callbacks callbacks{memoryRead, memorySeek, nullptr, memoryTell};
      require(ov_open_callbacks(&audioCursor, &audio, nullptr, 0, callbacks) == 0, "ouverture du décodeur Vorbis impossible");
      audioOpen = true;
      try {
        require(ov_streams(&audio) == 1, "audio Vorbis chaîné non pris en charge");
        const vorbis_info* info = ov_info(&audio, -1);
        require(info != nullptr && info->rate >= 8000 && info->rate <= 192000 && (info->channels == 1 || info->channels == 2),
          "fréquence ou canaux Vorbis non pris en charge (mono/stéréo requis)");
        sampleRate = static_cast<int>(info->rate); channels = info->channels;
      } catch (...) { ov_clear(&audio); audioOpen = false; throw; }
    }
  }
  ~Movie() { if (audioOpen) ov_clear(&audio); }
  std::vector<uint8_t> bytes;
  std::unique_ptr<TheoraFrames> video;
  std::vector<jint> colors;
  MemoryCursor audioCursor;
  OggVorbis_File audio{};
  bool audioOpen = false;
  int width = 0, height = 0, sampleRate = 0, channels = 0;
  int64_t durationMs = 0;
  std::atomic<bool> closed{false};
  std::mutex videoMutex, audioMutex;
};

std::mutex handlesMutex;
std::unordered_map<jlong, std::shared_ptr<Movie>> handles;
std::atomic<jlong> nextHandle{1};
std::shared_ptr<Movie> get(jlong handle) {
  std::lock_guard<std::mutex> guard(handlesMutex);
  const auto found = handles.find(handle);
  require(found != handles.end() && !found->second->closed, "décodeur OGV fermé ou identifiant périmé");
  return found->second;
}
void error(JNIEnv* env, const std::exception& failure) {
  if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), failure.what());
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_fr_astragames_app_wolfnative_WolfNativeTheora_open(JNIEnv* env, jobject, jbyteArray input) {
  try {
    require(input != nullptr, "données OGV absentes");
    const jsize length = env->GetArrayLength(input);
    require(length > 0 && static_cast<size_t>(length) <= MAX_FILE, "OGV vide ou dépassant 128 Mio");
    std::vector<uint8_t> bytes(static_cast<size_t>(length));
    env->GetByteArrayRegion(input, 0, length, reinterpret_cast<jbyte*>(bytes.data()));
    if (env->ExceptionCheck()) return 0;
    auto movie = std::make_shared<Movie>(std::move(bytes));
    const jlong handle = nextHandle.fetch_add(1);
    std::lock_guard<std::mutex> guard(handlesMutex); handles.emplace(handle, std::move(movie));
    return handle;
  } catch (const std::exception& failure) { error(env, failure); return 0; }
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_fr_astragames_app_wolfnative_WolfNativeTheora_info(JNIEnv* env, jobject, jlong handle) {
  try {
    const auto movie = get(handle);
    const jlong values[]{movie->width, movie->height, movie->durationMs, movie->sampleRate, movie->channels};
    jlongArray output = env->NewLongArray(5);
    if (output) env->SetLongArrayRegion(output, 0, 5, values);
    return output;
  } catch (const std::exception& failure) { error(env, failure); return nullptr; }
}

extern "C" JNIEXPORT jlong JNICALL
Java_fr_astragames_app_wolfnative_WolfNativeTheora_decodeFrame(JNIEnv* env, jobject, jlong handle, jlong position,
    jintArray pixels, jlong previousFrame) {
  try {
    const auto movie = get(handle); std::lock_guard<std::mutex> guard(movie->videoMutex);
    require(!movie->closed, "lecture OGV annulée");
    require(pixels != nullptr && env->GetArrayLength(pixels) == movie->width * movie->height, "taille du tampon ARGB invalide");
    const int64_t frame = movie->video->frameAt(std::clamp<int64_t>(position, 0, movie->durationMs - 1), movie->colors);
    if (frame != previousFrame)
      env->SetIntArrayRegion(pixels, 0, static_cast<jsize>(movie->colors.size()), movie->colors.data());
    return frame;
  } catch (const std::exception& failure) { error(env, failure); return -1; }
}

extern "C" JNIEXPORT jint JNICALL
Java_fr_astragames_app_wolfnative_WolfNativeTheora_readAudio(JNIEnv* env, jobject, jlong handle, jbyteArray output) {
  try {
    const auto movie = get(handle); std::lock_guard<std::mutex> guard(movie->audioMutex);
    require(!movie->closed && movie->audioOpen, "piste Vorbis fermée ou absente");
    require(output != nullptr, "tampon audio absent");
    const jsize length = env->GetArrayLength(output);
    require(length > 0 && length <= 262144, "taille du tampon PCM invalide");
    std::vector<char> pcm(static_cast<size_t>(length));
    int bitstream = 0;
    const long count = ov_read(&movie->audio, pcm.data(), length, 0, 2, 1, &bitstream);
    require(count >= 0 && bitstream == 0, "paquet Vorbis corrompu ou flux chaîné inattendu");
    if (count > 0) env->SetByteArrayRegion(output, 0, static_cast<jsize>(count), reinterpret_cast<jbyte*>(pcm.data()));
    return static_cast<jint>(count);
  } catch (const std::exception& failure) { error(env, failure); return -1; }
}

extern "C" JNIEXPORT void JNICALL
Java_fr_astragames_app_wolfnative_WolfNativeTheora_seekAudio(JNIEnv* env, jobject, jlong handle, jlong position) {
  try {
    const auto movie = get(handle); std::lock_guard<std::mutex> guard(movie->audioMutex);
    require(!movie->closed && movie->audioOpen, "piste Vorbis fermée ou absente");
    const ogg_int64_t sample = std::clamp<ogg_int64_t>(position * movie->sampleRate / 1000, 0, ov_pcm_total(&movie->audio, -1));
    require(ov_pcm_seek(&movie->audio, sample) == 0, "repositionnement Vorbis impossible");
  } catch (const std::exception& failure) { error(env, failure); }
}

extern "C" JNIEXPORT void JNICALL
Java_fr_astragames_app_wolfnative_WolfNativeTheora_close(JNIEnv*, jobject, jlong handle) {
  std::lock_guard<std::mutex> guard(handlesMutex);
  const auto found = handles.find(handle);
  if (found != handles.end()) { found->second->closed = true; handles.erase(found); }
}
