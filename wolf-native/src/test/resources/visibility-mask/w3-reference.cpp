// Independent transcription of W3 FUN975170 (placement) and FUN7801f0
// (8-bit AND/OR and clipping). This does not execute Windows Game.exe.
// cl /std:c++17 /EHsc /O2 w3-reference.cpp
#include <algorithm>
#include <cstdint>
#include <iomanip>
#include <iostream>
#include <sstream>
#include <vector>

static int width=7, height=5;
static bool exists=false;
static std::vector<uint8_t> buffer;
static std::string hex(const std::vector<uint8_t>& data) {
    std::ostringstream out; out << std::hex << std::setfill('0');
    for (unsigned value:data) out << std::setw(2) << value;
    return out.str();
}
static void sample() { std::cout << "sample\t" << (exists?hex(buffer):"-") << '\n'; }
static void ensure() { if (!exists) { buffer.assign(width*height,0); exists=true; } }
static void start(int id, int w=7, int h=5) {
    width=w;height=h;exists=false;buffer.clear();
    std::cout << "case\t" << id << '\t' << width << '\t' << height << '\n'; sample();
}
static void fill(bool hidden) {
    buffer.assign(width*height,hidden?255:0);exists=true;
    std::cout << "fill\t" << hidden << '\n'; sample();
}
static void reset() {
    if (exists) std::fill(buffer.begin(),buffer.end(),0);
    std::cout << "reset\n"; sample();
}
static void draw(int sw,int sh,std::vector<uint8_t> source,int x,int y,int anchor,int mode) {
    std::cout << "draw\t" << sw << '\t' << sh << '\t' << x << '\t' << y << '\t'
        << anchor << '\t' << mode << '\t' << hex(source) << '\n';
    ensure();
    int left=std::max(-9998,x-(anchor==1?sw/2:0));
    int top=y-(anchor==1?sh/2:0);
    // Native implementation traverses the clipped intersection in byte rows.
    int sx=std::max(0,-left),sy=std::max(0,-top);
    int dx=std::max(0,left),dy=std::max(0,top);
    int countX=std::min(sw-sx,width-dx),countY=std::min(sh-sy,height-dy);
    for(int row=0;row<countY;row++) for(int column=0;column<countX;column++) {
        uint8_t s=source[(sy+row)*sw+sx+column];
        uint8_t& d=buffer[(dy+row)*width+dx+column];
        d=mode==0?d&s:d|s;
    }
    sample();
}
int main() {
    int id=0;
    const std::vector<uint8_t> source{0,1,127,255,254,0,2,128,128,254,1,0};
    for(bool hidden:{false,true}) for(int x:{-9,-2,0,3,10}) for(int y:{-8,-1,0,2,7})
        for(int anchor:{0,1}) for(int mode:{0,1}) {
            start(id++);fill(hidden);draw(4,3,source,x,y,anchor,mode);
            draw(4,3,source,3-x,2-y,1-anchor,1-mode);reset();draw(4,3,source,0,0,0,0);
        }
    // All256 source values retain their raw bits through both combinations.
    for(int value=0;value<256;value++) for(int mode:{0,1}) {
        start(id++,1,1);draw(1,1,{0x55},0,0,0,1);
        draw(1,1,{static_cast<uint8_t>(value)},0,0,0,mode);
    }
}
