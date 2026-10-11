// Independent integer transcription of FUN009fb530's edge construction.
// W3 Game.exe SHA256 4ee2a905a5a74a513c885b7096150ffda1a9800180db5b5b0337ad8fd86fbf8e.
// Input cardinal flags are the output of 009fbb30; wrapping follows 009fbbf0.
// This isolates eight-way edge construction from the Kotlin implementation.
#include <array>
#include <cstdint>
#include <cstdio>
#include <vector>

static void fixture(const char *name,int width,int height,bool loopX,bool loopY,const std::vector<int>& flags) {
    auto read=[&](int x,int y) {
        if(loopX)x=(x+width)%width;
        if(loopY)y=(y+height)%height;
        if(x<0||y<0||x>=width||y>=height)return 0;
        return flags[x*height+y];
    };
    std::printf("%s\t%d\t%d\t%d\t%d\t",name,width,height,loopX,loopY);
    for(int i=0;i<width*height;i++)std::printf("%s%d",i?",":"",flags[i]);
    std::putchar('\t');
    for(int x=0;x<width;x++)for(int y=0;y<height;y++) {
        int center=read(x,y);
        bool up=(center&1)&&(read(x,y-1)&16);
        bool left=(center&64)&&(read(x-1,y)&4);
        bool right=(center&4)&&(read(x+1,y)&64);
        bool down=(center&16)&&(read(x,y+1)&1);
        int mask=(up?1:0)+(right?4:0)+(down?16:0)+(left?64:0);
        if((read(x-1,y)&1)&&(read(x-1,y-1)&16)&&(read(x,y-1)&64)&&(read(x-1,y-1)&4)&&up&&left)mask+=128;
        if((read(x+1,y)&1)&&(read(x+1,y-1)&16)&&(read(x,y-1)&4)&&(read(x+1,y-1)&64)&&up&&right)mask+=2;
        if((read(x-1,y)&16)&&(read(x-1,y+1)&1)&&(read(x,y+1)&64)&&(read(x-1,y+1)&4)&&down&&left)mask+=32;
        if((read(x+1,y)&16)&&(read(x+1,y+1)&1)&&(read(x,y+1)&4)&&(read(x+1,y+1)&64)&&down&&right)mask+=8;
        std::printf("%s%d",x||y?",":"",mask);
    }
    std::putchar('\n');
}
int main() {
    std::puts("# Native W3 eight-way grid construction from cardinal tile flags; X-major then Y");
    char name[80]; std::vector<int> flags(9,85);
    for(int loops=0;loops<4;loops++)fixture("open-3x3",3,3,loops&1,loops&2,flags);
    for(int cell=0;cell<9;cell++)for(int bit:{1,4,16,64}) {
        flags.assign(9,85);flags[cell]&=~bit;
        std::snprintf(name,sizeof name,"one-edge-closed-%d-%d",cell,bit);
        fixture(name,3,3,false,false,flags);
    }
    uint32_t seed=0x4ee2a905;
    for(int sample=0;sample<64;sample++) {
        for(int cell=0;cell<9;cell++) {
            seed^=seed<<13;seed^=seed>>17;seed^=seed<<5;
            int four=(seed>>24)&15;flags[cell]=0;
            for(int bit=0;bit<4;bit++)flags[cell]|=((four>>bit)&1)<<(bit*2);
        }
        for(int loops=0;loops<4;loops++) {
            std::snprintf(name,sizeof name,"mixed-cardinal-%d",sample);
            fixture(name,3,3,loops&1,loops&2,flags);
        }
    }
    fixture("one-cell-open",1,1,true,true,{85});
    fixture("one-cell-closed",1,1,true,true,{0});
    fixture("narrow-horizontal",4,1,true,false,{85,69,81,84});
    fixture("narrow-vertical",1,4,false,true,{85,69,81,84});
}
