// Independent byte-stream oracle derived from the pinned Wolf3 PE, not from Kotlin.
// Sources: W3 96a240 (layout), 96c320 (controls), 98f1a0 (tick), 98c140 (status);
// W2 49d5b0 (prefix/control clauses), 4cc6e0 (tick), 492000 (status).
// Fixtures below contain literal native marker bytes. They are not encoded by a
// copy of WolfTextPictureTiming.compile. The PE is not executed by this oracle.
// Build x86 VS18: cl /std:c++17 /EHsc /O2 /utf-8 /fp:strict native-reference.cpp
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <float.h>
#include <iomanip>
#include <iostream>
#include <map>
#include <stdexcept>
#include <string>
#include <vector>

using Bytes = std::vector<unsigned char>;
Bytes bytes(std::initializer_list<unsigned char> values) { return Bytes(values); }
Bytes ascii(const std::string& value) { return Bytes(value.begin(), value.end()); }
Bytes operator+(Bytes a, const Bytes& b) { a.insert(a.end(), b.begin(), b.end()); return a; }
struct Fixture { std::string source; Bytes compiled; bool sjis; int initial; };
struct State {
    double speed = 240, progress = 0, previous = 0;
    int correction = 0, wait = 0, checkpoint = 0;
    bool close = false;
    std::map<int, double> anchors;
};
int charSize(unsigned char b, bool sjis) {
    if (sjis) return (b >= 0x81 && b <= 0x9f) || (b >= 0xe0 && b <= 0xfc) ? 2 : 1;
    if (b < 0x80) return 1;
    if ((b & 0xe0) == 0xc0) return 2;
    if ((b & 0xf0) == 0xe0) return 3;
    if ((b & 0xf8) == 0xf0) return 4;
    if ((b & 0xfc) == 0xf8) return 5;
    if ((b & 0xfe) == 0xfc) return 6;
    return 1;
}
// The marker header is not a token abstraction: cursor follows every raw byte.
int markerPayload(const Bytes& raw, int cursor, int opcode) {
    if (opcode == 0x17 || opcode == 0x22) {
        int end = cursor;
        while (raw.at(end) != 1) ++end;
        return end + 1 - cursor;
    }
    switch (opcode) {
    case 0x02: case 0x0c: return 4;
    case 0x03: case 0x04: case 0x16: case 0x1f: return 3;
    case 0x11: case 0x12: return 2;
    case 0x07: return 1;
    default: return 0;
    }
}
int measure(const Fixture& f, int version) {
    int cursor = 0, correction = 0;
    while (cursor < static_cast<int>(f.compiled.size())) {
        unsigned char first = f.compiled.at(cursor);
        int size = charSize(first, f.sjis);
        cursor += size;
        if (version >= 300) correction += std::max(size - 2, 0);
        if (first != 1) continue;
        int opcode = f.compiled.at(cursor + 1);
        cursor += 2; correction += 3;
        int payload = markerPayload(f.compiled, cursor, opcode);
        cursor += payload;
        if (opcode == 0x16 || opcode == 0x1f) correction += version > 339 ? 2 : 0;
        else if (opcode == 0x17 || opcode == 0x22) correction += version > 339 ? payload - 1 : 0;
        else correction += payload;
    }
    return correction;
}
int draw(const Fixture& f, State& s, int version, int fallback) {
    int cursor = 0, visible = 0;
    bool immediate = false;
    s.correction = 0;
    while (cursor < static_cast<int>(f.compiled.size())) {
        if (s.wait > 0 && cursor >= s.checkpoint - 1) break;
        int before = cursor;
        unsigned char first = f.compiled.at(cursor);
        int size = charSize(first, f.sjis);
        cursor += size;
        if (version >= 300) s.correction += std::max(size - 2, 0);
        if (immediate && s.progress < cursor - static_cast<double>(s.correction))
            s.progress = cursor - static_cast<double>(s.correction);
        bool leadingInstant = version >= 300 && before == 0 && f.compiled.size() >= 3 &&
            f.compiled[0] == 1 && f.compiled[1] == 2 && f.compiled[2] == 0x20;
        if (!immediate && !leadingInstant && static_cast<int>(s.progress) + s.correction < cursor) break;
        if (first != 1) { if (first != 10 && first != 13) ++visible; continue; }
        int opcode = f.compiled.at(cursor + 1);
        cursor += 2;
        s.correction += 3;
        int payload = markerPayload(f.compiled, cursor, opcode);
        switch (opcode) {
        case 0x0c: {
            int value = ((f.compiled.at(cursor) - 14) * 64 + f.compiled.at(cursor + 1) - 14) * 64 + f.compiled.at(cursor + 2) - 14;
            if (value == 65535 || value == 0) value = fallback;
            value = std::max(value, 1);
            if (version >= 300) {
                if (s.anchors.count(cursor) == 0) s.anchors[cursor] = s.progress;
                double oldCorrection = s.correction;
                double sum = oldCorrection + s.anchors.at(cursor);
                double difference = sum - static_cast<double>(cursor);
                s.correction = static_cast<int>(oldCorrection - difference);
            }
            s.speed = value;
            s.correction += 4;
            break;
        }
        case 0x0e: if (s.checkpoint < cursor) { s.wait = 16; s.checkpoint = cursor + 1; } break;
        case 0x20: immediate = true; break;
        case 0x0b: immediate = false; break;
        case 0x10: s.close = true; break;
        case 0x01: ++visible; break; // literal backslash
        case 0x16: case 0x1f: s.correction += version > 339 ? 2 : 0; ++visible; break;
        case 0x17: case 0x22: s.correction += version > 339 ? payload - 1 : 0; ++visible; break;
        default: s.correction += payload; break;
        }
        cursor += payload;
    }
    return visible;
}
void tick(State& s, int step) {
    s.previous = s.progress;
    if (s.wait < 1) s.progress = std::min(50000000.0, s.progress + step * s.speed / 30.0);
    if (s.wait > 0) s.wait -= step;
}
int status(const Fixture& f, const State& s, int step) {
    return f.compiled.size() <= static_cast<double>(static_cast<int>(s.progress) + s.correction) - step * s.speed / 30.0 + 1.0;
}
std::string hex(const std::string& text) {
    std::string result;
    for (unsigned char ch : text) { result += "0123456789abcdef"[ch >> 4]; result += "0123456789abcdef"[ch & 15]; }
    return result;
}
void doubleBits(double value) {
    uint64_t bits; std::memcpy(&bits, &value, 8);
    std::cout << std::hex << std::setfill('0') << std::setw(16) << bits << std::dec;
}
int main() {
    _controlfp(_PC_53, _MCW_PC);
    const Bytes pause = bytes({1,2,0x0e}), instant = bytes({1,2,0x20}), normal = bytes({1,2,0x0b});
    const Bytes fallback = bytes({1,6,0x0c,0x1d,0x4d,0x4d,1});
    const std::vector<Fixture> fixtures = {
        {"", {}, false, 0},
        {"ABCD", ascii("ABCD"), false, 240},
        {u8"Aé日😀", bytes({0x41,0xc3,0xa9,0xe6,0x97,0xa5,0xf0,0x9f,0x98,0x80}), false, 30},
        {u8"ＡあA", bytes({0x82,0x60,0x82,0xa0,0x41}), true, 30},
        {"<C>\\c[1]\\f[10]A\r\nB", bytes({1,2,0x14,1,6,2,0x0e,0x0e,0x0f,1,1,5,3,0x0e,0x18,1}) + ascii("A\r\nB"), false, 30},
        {"A\\.B\\.C", ascii("A") + pause + ascii("B") + pause + ascii("C"), false, 240},
        {"\\>ABC\\<D", instant + ascii("ABC") + normal + ascii("D"), false, 0},
        {"A\\sp[60]BC\\sp[0]DE", ascii("A") + bytes({1,6,0x0c,0x0e,0x0e,0x4a,1}) + ascii("BC") + fallback + ascii("DE"), false, 30},
        {"\\sp[-1]A\\.B\\^", fallback + ascii("A") + pause + ascii("B") + bytes({1,2,0x10}), false, 240},
        {"X\\\\Y\\A+\\N", ascii("X") + bytes({1,2,1}) + ascii("Y") + bytes({1,2,0x1a,1,2,5}), false, 30},
        {"\\i[0]A\\img[a.png]B\\iS[1]", bytes({1,5,0x16,0x0e,0x0e,1}) + ascii("A") + bytes({1,2,0x17}) + ascii("a.png") + bytes({1}) + ascii("B") + bytes({1,5,0x1f,0x0e,0x0f,1}), false, 30},
        {u8"<R>あ\\sp[60001]Ｂ\\<C", bytes({1,2,0x15,0x82,0xa0,1,6,0x0c,0x1c,0x37,0x2e,1,0x82,0x61,1,2,0x0b,0x43}), true, 1},
    };
    std::cout << "# Derived C++ x86 raw-byte oracle; not an execution of the original PE.\n";
    std::cout << "# id frame version step initial fallback sourceUTF8hex charset compiled visible status speedBits progressBits previousBits correction wait checkpoint autoClose\n";
    int id = 0;
    for (const auto& f : fixtures) for (int version : {229,330,369}) for (int step : {1,2}) {
        State s; s.speed = f.initial; s.correction = measure(f, version);
        for (int frame = 0; frame < 64; ++frame) {
            int liveFallback = frame < 20 ? 60 : frame < 40 ? 75 : 0;
            tick(s, step); int visible = draw(f, s, version, liveFallback);
            std::cout << id << '\t' << frame << '\t' << version << '\t' << step << '\t' << f.initial << '\t' << liveFallback << '\t'
                      << hex(f.source) << '\t' << (f.sjis ? "windows-31j" : "UTF-8") << '\t' << f.compiled.size() << '\t'
                      << visible << '\t' << status(f, s, step) << '\t';
            doubleBits(s.speed); std::cout << '\t'; doubleBits(s.progress); std::cout << '\t'; doubleBits(s.previous);
            std::cout << '\t' << s.correction << '\t' << s.wait << '\t' << s.checkpoint << '\t' << (s.close ? 1 : 0) << '\n';
        }
        ++id;
    }
}
