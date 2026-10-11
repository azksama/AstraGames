#include <cstdint>
#include <cstdio>
#include <cstring>
#include <climits>
#include <initializer_list>

// Independent arithmetic transcription of pinned W3 Game.exe 9ce6e0/9cf200,
// 9ce7f0/9cfe90. It does not execute or redistribute the proprietary engine.
// MSVC x86 /std:c++17 /EHsc /O2; unsigned operations explicitly preserve IMUL
// and ADD wrapping without relying on undefined C++ signed overflow.
static int32_t bits(uint32_t n) { int32_t out; std::memcpy(&out,&n,4); return out; }
static int32_t add(int32_t a,int32_t b) { return bits(uint32_t(a)+uint32_t(b)); }
static int32_t mul(int32_t a,int32_t b) { return bits(uint32_t(a)*uint32_t(b)); }
static int32_t quot(int32_t a,int32_t b) { return int32_t(int64_t(a)/b); }
struct State { int kind,active,w,h,ax,ay,px,py,divisor; };
struct Params { int sx,sy,fps,cx,cy,shakeX,shakeY,scale,zoom,ox,oy; };
static void axis(int& a,int& p,int dim,int speed,int fps,int camera,int divisor,int shake,int kind) {
    const int32_t range=mul(dim,8);
    a=add(add(a,kind==0?mul(speed,fps):quot(speed,fps)),range);
    int32_t q=add(add(a,divisor==0?0:quot(mul(-2,camera),divisor)),shake);
    // Closed form of native while(q<0) q+=range, with a widened count only.
    if(q<0) q=int32_t(int64_t(q)+((-int64_t(q)+range-1)/range)*range);
    if(range<q) a=add(a,q%range-q);
    p=q%range;
}
static void positions(int out[3],int p,int dim,int origin,const Params& v,bool unit) {
    const int cut=p/8;
    if(unit) { out[0]=0;out[1]=cut;out[2]=dim;return; }
    const int delta=add(v.zoom,-100);
    out[0]=-quot(mul(mul(delta,origin),v.scale),100);
    out[1]=add(quot(mul(p,v.scale),8),quot(mul(mul(add(cut,-origin),delta),v.scale),100));
    out[2]=add(mul(dim,v.scale),quot(mul(mul(add(dim,-origin),delta),v.scale),100));
}
static void emit(const char* name,State s,Params v,bool tick) {
    std::printf("%s\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d",
        name,tick?1:0,s.kind,s.active,s.w,s.h,s.ax,s.ay,s.px,s.py,s.divisor,
        v.sx,v.sy,v.fps,v.cx,v.cy,v.shakeX,v.shakeY,v.scale,v.zoom,v.ox,v.oy);
    if(tick&&s.active&&s.w&&s.h) {
        axis(s.ax,s.px,s.w,v.sx,v.fps,v.cx,s.divisor,v.shakeX,s.kind);
        axis(s.ay,s.py,s.h,v.sy,v.fps,v.cy,s.divisor,v.shakeY,s.kind);
    }
    const int clockX=s.px,clockY=s.py;
    const bool unit=mul(v.scale,v.zoom)==100;
    if(!unit) { if(4<=s.px&&s.px<8)s.px=8; if(4<=s.py&&s.py<8)s.py=8; }
    std::printf("\t%d\t%d\t%d\t%d\t%d\t%d",s.ax,s.ay,clockX,clockY,s.px,s.py);
    int x[3],y[3]; positions(x,s.px,s.w,v.ox,v,unit);positions(y,s.py,s.h,v.oy,v,unit);
    const int cx=s.px/8,cy=s.py/8;
    const int rects[4][8]={
        {0,0,s.w-cx,s.h-cy,x[1],y[1],x[2],y[2]},
        {s.w-cx,s.h-cy,s.w,s.h,x[0],y[0],x[1],y[1]},
        {s.w-cx,0,s.w,s.h-cy,x[0],y[1],x[1],y[2]},
        {0,s.h-cy,s.w-cx,s.h,x[1],y[0],x[2],y[1]}
    };
    for(auto& r:rects) for(int n:r) std::printf("\t%d",n);
    std::puts("");
}
int main() {
    std::puts("name\ttick\tkind\tactive\tw\th\tax\tay\tpx\tpy\tdivisor\tsx\tsy\tfps\tcx\tcy\tshakeX\tshakeY\tscale\tzoom\tox\toy\toutAX\toutAY\toutClockPX\toutClockPY\toutPX\toutPY\tr1sx\tr1sy\tr1ex\tr1ey\tr1dx\tr1dy\tr1dex\tr1dey\tr2sx\tr2sy\tr2ex\tr2ey\tr2dx\tr2dy\tr2dex\tr2dey\tr3sx\tr3sy\tr3ex\tr3ey\tr3dx\tr3dy\tr3dex\tr3dey\tr4sx\tr4sy\tr4ex\tr4ey\tr4dx\tr4dy\tr4dex\tr4dey");
    int serial=0;
    const int speeds[]={-999,-17,0,31};
    const int divs[]={0,-3,7};
    const int zooms[]={75,100,151};
    for(int kind=0;kind<2;++kind) for(int fps=1;fps<=2;++fps)
    for(int speed:speeds) for(int div:divs) for(int scale=1;scale<=2;++scale)
    for(int zoom:zooms) {
        char name[64]; std::sprintf(name,"scroll-%03d",serial++);
        State s{kind,1,37,29,311,-411,0,0,div};
        Params p{speed,-speed+3,fps,-123,217,7,-9,scale,zoom,18,13};
        emit(name,s,p,true);
    }
    // Draw boundary normalization, including 1px extents and negative origins.
    for(int dim: {1,31}) for(int px=0;px<9;++px) for(int zoom:zooms) {
        char name[64]; std::sprintf(name,"geometry-%03d",serial++);
        State s{1,1,dim,dim,0,0,px,px,0};
        Params p{0,0,1,0,0,0,0,1,zoom,-17,35};
        emit(name,s,p,false);
    }
    // Native Int32 wrapping precedes division and phase modulo.
    for(int kind=0;kind<2;++kind) for(int sign: {-1,1}) {
        char name[64]; std::sprintf(name,"overflow-%03d",serial++);
        State s{kind,1,320,240,sign>0?INT_MAX-3:INT_MIN+5,sign>0?INT_MIN+8:INT_MAX-7,0,0,3};
        Params p{INT_MAX,-2147483647,2,sign>0?INT_MAX:INT_MIN,123,INT_MAX,INT_MIN,2,151,160,120};
        emit(name,s,p,true);
    }
    return 0;
}
