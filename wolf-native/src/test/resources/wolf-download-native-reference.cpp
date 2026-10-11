// Independent oracle extracted from REA W3 426e20 and a2a300.
// This executes only this authored program; it never executes Game.exe or game assets.
// Build with VS18 x86, cl /std:c++17 /EHsc /O2; output is wolf-download-native.tsv.
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <cstdio>
#include <string>

static std::string encode(const std::wstring& input, unsigned cp) {
    int size=WideCharToMultiByte(cp,0,input.data(),static_cast<int>(input.size()),nullptr,0,nullptr,nullptr);
    std::string result(size,'\0');
    if(size) WideCharToMultiByte(cp,0,input.data(),static_cast<int>(input.size()),result.data(),size,nullptr,nullptr);
    return result;
}
static std::string escaped(const std::string& input) {
    const char* digits="0123456789ABCDEF";
    std::string result;
    for(unsigned char b:input) {
        if((b>='0'&&b<='9')||(b>='A'&&b<='Z')||(b>='a'&&b<='z')||b=='*'||b=='.'||b=='-'||b=='_'||b=='&'||b=='=') result+=b;
        else if(b==' ') result+='+';
        else { result+='%';result+=digits[b>>4];result+=digits[b&15]; }
    }
    return result;
}
static std::string hex(const std::string& input) {
    const char* digits="0123456789ABCDEF";std::string result;
    for(unsigned char b:input) {result+=digits[b>>4];result+=digits[b&15];}
    return result;
}
int main() {
    std::puts("# Native C oracle: 256 bytes + 128 W3 mode/extension combinations. No target execution.");
    for(int i=0;i<256;++i) {
        std::string value(1,static_cast<char>(i));
        std::printf("PERCENT\t%d\t%s\t%s\n",i,hex(value).c_str(),hex(escaped(value)).c_str());
    }
    const int bits[]={2,4,16,32,64,256};
    for(int mode:{-1,0}) for(int mask=0;mask<64;++mask) {
        int flags=0;for(int i=0;i<6;++i) if(mask&(1<<i)) flags|=bits[i];
        bool urlUtf=mode!=0&&(flags&2),receiveUtf=mode!=0&&(flags&256),postUtf=mode!=0&&(flags&32);
        std::string url=encode(L"https://example.invalid/run?name=\u65e5\u672c\u8a9e",urlUtf?65001:932);
        if(flags&4) {size_t q=url.find('?');if(q!=std::string::npos)url=url.substr(0,q+1)+escaped(url.substr(q+1));}
        std::string post="NONE";
        if(flags&16) {post=encode(L"msg=\u65e5\u672c\u8a9e&value=a b+;",postUtf?65001:932);if(flags&64)post=escaped(post);post=hex(post);}
        std::printf("REQUEST\t%d\t%d\t%s\t%s\t%s\t%s\t%s\n",mode,flags,urlUtf?"UTF8":"CP932",receiveUtf?"UTF8":"CP932",
            flags&16?(postUtf?"UTF8":"CP932"):"NONE",hex(url).c_str(),post.c_str());
    }
}
