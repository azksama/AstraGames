// Authored-media wrapper fixture generator. Includes the independent MIT
// WOLFX oracle whose default-key output is verified against pinned decryptFull.
// Never executes a game PE. cl /std:c++20 /EHsc /O2 /D WOLFX_UPSTREAM
// /I build/wolf-rea/upstream/UberWolf/UberWolfLib/WolfX/detail ...
#ifndef WOLFX_UPSTREAM
#error This media fixture generator requires the pinned upstream decryptFull verification
#endif
#define main wolfx_vectors_main
#include "native-reference.cpp"
#undef main
#include <filesystem>
#include <fstream>
#include <iterator>

int wmain(int argc, wchar_t** argv) {
    if (argc < 3) return 2;
    const std::filesystem::path destination(argv[1]);
    std::filesystem::create_directories(destination);
    for (int index = 2; index < argc; ++index) {
        const std::filesystem::path input(argv[index]);
        std::ifstream stream(input, std::ios::binary);
        const Bytes payload((std::istreambuf_iterator<char>(stream)), std::istreambuf_iterator<char>());
        if (payload.empty()) throw std::runtime_error("Empty authored fixture");
        auto write = [&](const std::wstring& suffix, const Bytes& key,
                         std::uint16_t stringIndex, std::uint32_t numericIndex,
                         const Bytes& text, std::uint32_t number) {
            const auto encrypted = make(key, stringIndex, numericIndex, text, number,
                                        payload, 0x27b89c04);
            const auto output = destination / (input.filename().wstring() + suffix);
            std::ofstream file(output, std::ios::binary);
            file.write(reinterpret_cast<const char*>(encrypted.data()), encrypted.size());
            if (!file) throw std::runtime_error("Failed to save encrypted fixture");
            std::wcout << input.filename().wstring() << L"\t" << suffix << L"\t"
                       << payload.size() << L"\t" << encrypted.size()
                       << L"\tPINNED-UPSTREAM-PASS\n";
        };
        write(L".wolfx", {}, 65535, 16777215, {}, 0);
        write(L".string-key.wolfx", {}, 0, 16777215,
              Bytes{'r','e','q','u','i','r','e','d'}, 0);
        write(L".number-key.wolfx", {}, 65535, 42, {}, 7);
        write(L".folder-key.wolfx", Bytes{'p','r','i','v','a','t','e'}, 65535,
              16777215, {}, 0);
    }
    return 0;
}
