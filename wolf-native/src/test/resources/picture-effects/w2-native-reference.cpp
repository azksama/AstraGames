// Independent transcription of W2's setters, updater and drawing arithmetic.
// Reference Game.exe SHA256 795203a6e875618d7fbdc7dd1f409a445c60262c911745553d040081bedb6ac0.
// REA 0x4c1dd0, 0x4ce890, 0x4920a0; binary64 colour factor @0x817708.
// Build for x86 with MSVC: colour arithmetic executes x87, as in the reference.
#include <cstdio>
#include <algorithm>
#include <cstdlib>
#include <cstdint>

static int channel(int n, int weight, int period, int previous) {
    static const double factor = 2.55;
    int result; unsigned short oldWord, truncateWord;
    __asm {
        fild dword ptr n
        fmul qword ptr factor
        fadd st(0), st(0)
        fimul dword ptr weight
        fidiv dword ptr period
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
static int packed(int r,int g,int b){return (std::clamp(r/2+100,0,200)<<16)|(std::clamp(g/2+100,0,200)<<8)|std::clamp(b/2+100,0,200);}
static int interp(int to,int from,int remaining,int total){return to-(to-from)*remaining/std::max(total,1);}
struct Picture {
    int w[0xd7]{}; int x=20,y=30,scaleX=100,scaleY=100,pattern=1;
    Picture(){w[0xaf]=w[0xb0]=0x646464;w[0xc4]=w[0xc5]=100;}
    void set(int op,int a,int b,int c,int t) {
        std::printf("set\t%d\t%d\t%d\t%d\t%d\n",op,a,b,c,t);
        switch(op) {
        case 0:w[0xac]=packed(a,b,c);w[0xad]=w[0xae]=t;break;
        case 1:{int prior=0;for(int shift=16;shift>=0;shift-=8)prior|=std::clamp(interp(w[0xaf]>>shift&255,w[0xb0]>>shift&255,w[0xb2],w[0xb1]),0,200)<<shift;
            w[0xb0]=prior;w[0xaf]=packed(a,b,c);w[0xb1]=w[0xb2]=t;break;}
        case 2:w[0xb4]=interp(w[0xb3],w[0xb4],w[0xb8],w[0xb7]);w[0xb6]=interp(w[0xb5],w[0xb6],w[0xb8],w[0xb7]);w[0xb3]=a;w[0xb5]=b;w[0xb7]=w[0xb8]=t;break;
        case 3:w[0xb9]=a;w[0xba]=b;w[0xbd]=c;w[0xbe]=std::max(t,1);w[0xbf]=w[0xbe]*2;break;
        case 4:w[0xc5]=interp(w[0xc4],w[0xc5],w[0xc7],w[0xc6]);if(w[0xc5]==100){w[0xc1]=a;w[0xc3]=b;}else{w[0xc1]=interp(w[0xc0],w[0xc1],w[0xc7],w[0xc6]);w[0xc3]=interp(w[0xc2],w[0xc3],w[0xc7],w[0xc6]);}w[0xc0]=a;w[0xc2]=b;w[0xc4]=c;w[0xc6]=w[0xc7]=t;break;
        case 5:case 6:w[0xc8]=packed(a,b,c);w[0xc9]=t;w[0xca]=0;w[0xcb]=op-5;break;
        case 7:w[0xcc]=a;w[0xcd]=b;w[0xd0]=c;w[0xcf]=t;w[0xce]=t*2;break;
        case 8:case 9:case 10:w[0xd1]=a;w[0xd2]=b;w[0xd4]=w[0xd3]=t;w[0xd6]=a;w[0xd5]=op-8;break;
        }
    }
    void tick() {
        std::puts("tick");
        if(w[0xae]>0)--w[0xae];if(w[0xb2]>0)--w[0xb2];if(w[0xb8]>0)--w[0xb8];
        if(w[0xbd]>0&&--w[0xbf]<1){--w[0xbd];w[0xbf]=w[0xbe]*2;}
        if(w[0xc7]>0&&--w[0xc7]<1)w[0xc5]=w[0xc4];
        if(w[0xc9]>0)w[0xca]=(w[0xca]+1)%(w[0xcb]==0?w[0xc9]*2:w[0xc9]);
        if(w[0xcf]>0&&--w[0xce]<=-w[0xd0])w[0xce]=w[0xcf]*2;
        if(w[0xd4]>0&&--w[0xd3]==0) {
            const int diff=w[0xd2]-w[0xd1],step=diff/std::max(std::abs(diff),1);
            switch(w[0xd5]+2){
            case 0:if(w[0xd6]==w[0xd1]){w[0xd6]+=step;w[0xd5]=2;}else w[0xd6]-=step;w[0xd3]=w[0xd4];break;
            case 2:if(w[0xd6]==w[0xd2])w[0xd4]=0;else{w[0xd6]+=step;w[0xd3]=w[0xd4];pattern=w[0xd6];}break;
            case 3:w[0xd6]=w[0xd6]==w[0xd2]?w[0xd1]:w[0xd6]+step;w[0xd3]=w[0xd4];break;
            case 4:if(w[0xd6]==w[0xd2]){w[0xd6]-=step;w[0xd5]=-2;}else w[0xd6]+=step;w[0xd3]=w[0xd4];break;
            }
        }
    }
    int tint(int shift){int value=0;if(w[0xae]>0)value=channel((w[0xac]>>shift&255)-100,w[0xae],w[0xad],0);
        int t=w[0xb2]>0?interp(w[0xaf]>>shift&255,w[0xb0]>>shift&255,w[0xb2],w[0xb1]):(w[0xaf]>>shift&255);
        value=channel(t-100,1,1,value);if(w[0xc9]>0)value=channel((w[0xc8]>>shift&255)-100,w[0xcb]==0?std::abs(w[0xca]-w[0xc9]):w[0xc9]-w[0xca],w[0xc9],value);return value;}
    void draw(int frame){
        int dx=interp(w[0xb3],w[0xb4],w[0xb8],w[0xb7]),dy=interp(w[0xb5],w[0xb6],w[0xb8],w[0xb7]);
        if(w[0xbd]>0){int s=w[0xbd]%2==0?-1:1;auto f=[&](int v){return w[0xbf]<w[0xbe]?v*w[0xbf]/w[0xbe]:v-(w[0xbf]-w[0xbe])*v/w[0xbe];};dx+=s*f(w[0xb9]);dy+=s*f(w[0xba]);}
        int zoom=100,cx=0,cy=0;
        if(w[0xc4]!=100||w[0xc5]!=100){zoom=interp(w[0xc4],w[0xc5],w[0xc7],w[0xc6]);cx=interp(w[0xc0],w[0xc1],w[0xc7],w[0xc6]);cy=interp(w[0xc2],w[0xc3],w[0xc7],w[0xc6]);}
        auto wave=[&](int amp){return w[0xcf]!=0&&w[0xce]>=0?(w[0xcf]-std::abs(w[0xce]-w[0xcf]))*amp/w[0xcf]:0;};
        float sx=((float)scaleX+wave(w[0xcc]))/100*zoom/100,sy=((float)scaleY+wave(w[0xcd]))/100*zoom/100;
        int px=x+(x-cx)*(zoom-100)/100+dx,py=y+(y-cy)*(zoom-100)/100+dy;
        int pat=w[0xd4]>0?w[0xd6]:pattern;
        std::printf("sample\t%d\t%d\t%d\t%.6f\t%.6f\t%d\t%d\t%d\t%d",frame,px,py,sx,sy,tint(16),tint(8),tint(0),pat);
        for(int k=0xac;k<=0xd6;k++)std::printf("\t%d",w[k]);std::puts("");
    }
};
static Picture start(const char*name){std::printf("case\t%s\t20\t30\t100\t100\t1\n",name);return Picture{};}
static void frames(Picture&p,int count){for(int t=0;t<=count;t++){p.draw(t);if(t<count)p.tick();}}
int main(){
    static_assert(sizeof(void*)==4,"x86 x87 fixture required");
    std::puts("# W2 native-picture-effects REA independent C++/x87 vectors v2; long repeat counters");
    auto p=start("flash-tone-blink-overlap");p.set(0,200,-200,50,5);p.set(1,-101,199,0,7);p.set(5,40,-40,90,4);frames(p,18);
    p=start("two-way-blink");p.set(5,100,40,-100,6);frames(p,25);
    p=start("saw-blink");p.set(6,-199,199,99,4);frames(p,14);
    p=start("translation-interruption");p.set(2,10,-15,0,4);p.draw(0);p.tick();p.draw(1);p.tick();p.draw(2);p.set(2,30,12,0,3);frames(p,5);
    p=start("zoom-interruption");p.set(4,25,-30,150,4);p.draw(0);p.tick();p.draw(1);p.tick();p.draw(2);p.set(4,100,120,50,5);frames(p,7);
    p=start("shake-triangle-three-repeats");p.set(3,12,-9,3,4);frames(p,27);
    p=start("official-menu-shake-long-repeat");p.set(3,30,21,999999,1);frames(p,21);
    p=start("shake-maximum-signed-repeat");p.set(3,30,21,INT32_MAX,1);frames(p,21);
    p=start("wave-gap-three");p.set(7,40,-20,3,4);frames(p,25);
    p=start("shift-wave-zoom-compose");p.set(2,-10,17,0,5);p.set(4,15,20,125,3);p.set(7,30,60,2,3);frames(p,20);
    for(int op=8;op<=10;op++)for(int reversed=0;reversed<2;reversed++){char name[80];std::sprintf(name,"pattern-op%d-%s",op,reversed?"descending":"ascending");p=start(name);p.set(op,reversed?5:2,reversed?2:5,0,2);frames(p,20);}
    p=start("pattern-pingpong-one-cell");p.set(10,3,3,0,1);frames(p,8);
    p=start("neutral-stop");p.set(0,200,200,200,0);p.set(1,0,0,0,0);p.set(2,0,0,0,0);p.set(4,0,0,100,0);p.set(5,100,100,100,0);p.set(7,30,30,0,0);p.set(8,2,7,0,0);frames(p,3);
    for(int offset=-100;offset<=100;offset++){char name[60];std::sprintf(name,"x87-flash-offset-%d",offset);p=start(name);p.set(0,offset*2,offset*2,offset*2,13);frames(p,13);}
}
