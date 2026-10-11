// Independent W2 actor setter/tick/draw transcription; no Kotlin implementation is called.
// Game.exe SHA256 795203a6e875618d7fbdc7dd1f409a445c60262c911745553d040081bedb6ac0.
// REA: 0x4c3020, 0x4c3060, 0x4c30f0, 0x4475b0, 0x4c6e80.
// MSVC x86 executes the same x87 conversion (binary64 factor at 0x817708).
#include <cstdio>
#include <algorithm>
#include <cstdlib>
#include <cstdint>
#ifndef WOLF_CHARACTER_W3
#define WOLF_CHARACTER_W3 0
#endif

static int channel(int percent, int previous) {
    static const double factor = 2.55;
    int result; unsigned short oldWord, truncateWord;
    __asm {
        fild dword ptr percent
        fmul qword ptr factor
        fiadd dword ptr previous
        fnstcw oldWord
        mov ax, oldWord
        or ax, 0c00h
        mov truncateWord, ax
        fldcw truncateWord
        fistp dword ptr result
        fldcw oldWord
    }
    return result;
}
static int packed(int r,int g,int b) {return ((std::clamp(r,-100,100)+100)<<16)|((std::clamp(g,-100,100)+100)<<8)|(std::clamp(b,-100,100)+100);}
struct Character {
    int w[14]{};
    void set(int op,int a,int b,int c,int t) {
        std::printf("set\t%d\t%d\t%d\t%d\t%d\n",op,a,b,c,t);
        if(op==0){w[0]=packed(a,b,c);w[1]=w[2]=t;}
        if(op==1){w[3]=a;w[4]=b;w[5]=c;w[6]=std::max(t,1);w[7]=w[6]*2;}
        if(op==2||op==3){w[10]=packed(a,b,c);w[11]=t;w[12]=0;w[13]=op-2;}
    }
    void tick() {
        std::puts("tick");
        if(w[2]>0)--w[2];
        if(w[11]>0)w[12]=(w[12]+1)%(w[13]==0?w[11]*2:w[11]);
        if(w[5]<1){w[8]=0;w[9]=0;}
        else {
            int sign=w[5]%2?1:-1;
            auto amp=[&](int value){return w[7]<w[6]?value*w[7]/w[6]:value-(w[7]-w[6])*value/w[6];};
            w[8]=sign*amp(w[3]);w[9]=sign*amp(w[4]);
            --w[7];if(w[7]<1){
                // W3 009ea7f0 differs here from W2 004475b0.
                if(WOLF_CHARACTER_W3&&w[5]>99999)w[5]=static_cast<int>(static_cast<unsigned>(w[5])+((~static_cast<unsigned>(w[5])&1u)*2u)-1u);
                else --w[5];
                w[7]=w[6]*2;
            }
        }
    }
    int tone(int shift) {
        if(w[2]<1&&w[11]<1)return 0;
        int ratio=w[2]*1000/std::max(w[1],1);
        int v=channel(((w[0]>>shift&255)-100)*ratio/1000,0);
        ratio=w[13]==0?std::abs(w[12]-w[11])*1000/std::max(w[11],1):(w[11]-w[12])*1000/std::max(w[11],1);
        return channel(((w[10]>>shift&255)-100)*ratio/1000,v);
    }
    void draw(int frame) {
        std::printf("sample\t%d\t%d\t%d\t%d\t%d\t%d",frame,20+w[8],30+w[9],tone(16),tone(8),tone(0));
        for(int value:w)std::printf("\t%d",value);
        std::puts("");
    }
};
static Character start(const char* name){std::printf("case\t%s\n",name);return Character{};}
static void frames(Character& c,int count){for(int t=0;t<=count;t++){c.draw(t);if(t<count)c.tick();}}
int main(){
    static_assert(sizeof(void*)==4,"Compile MSVC x86 for native x87 reference");
    std::puts(WOLF_CHARACTER_W3?"# W3 native updater and shared colour equations C++/x87 v2":"# W2 native character effects independent C++/x87 v1");
    auto c=start("flash-and-triangle-blink");c.set(0,200,-300,51,7);c.set(2,49,-73,100,4);frames(c,19);
    c=start("saw-blink");c.set(3,-100,21,70,5);frames(c,24);
    c=start("odd-repeat-shake");c.set(1,12,-9,3,4);frames(c,28);
    c=start("even-repeat-shake");c.set(1,-12,9,2,3);frames(c,17);
    c=start("shake-interruption");c.set(1,12,9,3,4);frames(c,3);c.set(1,5,7,1,2);frames(c,7);
    c=start("zero-duration-shake");c.set(1,17,-6,1,0);frames(c,7);
    c=start("zero-count-shake");c.set(1,20,30,0,5);frames(c,4);
    c=start("long-count-shake");c.set(1,12,9,999999,1);frames(c,7);
    c=start("max-count-shake");c.set(1,12,9,INT32_MAX,1);frames(c,7);
    if(WOLF_CHARACTER_W3){
        c=start("finite-threshold-shake");c.set(1,12,9,99999,1);frames(c,7);
        c=start("infinite-threshold-shake");c.set(1,12,9,100000,1);frames(c,7);
        c=start("max-even-count-shake");c.set(1,12,9,INT32_MAX-1,1);frames(c,7);
    }
    c=start("stop-flash-and-blink");c.set(0,100,100,100,0);c.set(2,100,100,100,0);frames(c,4);
    c=start("clamp-signed-inputs");c.set(0,INT32_MIN,INT32_MAX,3,3);frames(c,5);
    for(int offset=-100;offset<=100;offset++){char name[60];std::sprintf(name,"x87-percent-%d",offset);c=start(name);c.set(0,offset,offset,offset,13);frames(c,13);}
}
