// Independent x86/SSE2 transcription of original W3 setters0099b680,
// tick0098f1a0 and draw0098fd20, on the public NativePicture float inputs.
// This generates a numerical oracle; it does not execute Windows Game.exe.
// cl /std:c++17 /EHsc /O2 /fp:strict /arch:SSE2 w3-reference.cpp
#include <array>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <float.h>
#include <iomanip>
#include <iostream>
#include <emmintrin.h>

static std::array<int32_t, 0x164> m{};
static unsigned char flag508, flag509, flag50a;
static int nativeInt(double x) { return _mm_cvttsd_si32(_mm_set_sd(x)); }
static int32_t sub32(int32_t a, int32_t b) { return static_cast<int32_t>(static_cast<uint32_t>(a) - static_cast<uint32_t>(b)); }
static int32_t product32(int32_t a, int32_t b) { return static_cast<int32_t>(static_cast<uint32_t>(a) * static_cast<uint32_t>(b)); }
static int32_t currentInt(int t, int left, int total) {
    return sub32(m[t], product32(sub32(m[t], m[t+1]), m[left]) / std::max(1, m[total]));
}
static unsigned channel(int value) { return static_cast<unsigned>(std::clamp(value / 2 + 100, 0, 200)); }
static void clear() {
    m.fill(0); m[0x115]=m[0x116]=m[0x117]=m[0x118]=100;
    flag508=flag509=flag50a=0;
}
static void set(int op, int a, int b, int c, int duration) {
    std::cout << "set\t" << op << '\t' << a << '\t' << b << '\t' << c << '\t' << duration << '\n';
    int target=-1, total=-1, left=-1;
    switch(op) {
    case 11: target=0x10f; total=0x113; left=0x114; break;
    case 12: target=0x115; total=0x119; left=0x11a; break;
    case 13: target=0x11b; total=0x11d; left=0x11e; break;
    case 14: target=0x11f; total=0x121; left=0x122; break;
    case 17:
        m[0xe4]=(channel(a)<<16)|(channel(b)<<8)|channel(c);
        m[0xe5]=m[0xe6]=duration; return;
    default: std::abort();
    }
    const int from = currentInt(target,left,total);
    const int otherFrom = op==11||op==12 ? currentInt(target+2,left,total) : 0;
    m[target+1]=from; m[target]=a;
    if(op==11||op==12) { m[target+3]=otherFrom; m[target+2]=b; }
    m[total]=m[left]=duration;
}
static void tick() {
    std::cout << "tick\n";
    if(m[0xe6]>0) --m[0xe6];
    flag50a=1;
    if(m[0x114]<1) { if(m[0x10f]==0&&m[0x111]==0) flag50a=0; }
    else if(--m[0x114]<1) { m[0x110]=m[0x10f]; m[0x112]=m[0x111]; }
    flag508=1;
    if(m[0x11a]<1) { if(m[0x115]==100&&m[0x117]==100) flag508=0; }
    else if(--m[0x11a]<1) { m[0x116]=m[0x115]; m[0x118]=m[0x117]; }
    flag509=1;
    if(m[0x11e]>0 && --m[0x11e]<1) m[0x11c]=m[0x11b];
    if(m[0x122]>0 && --m[0x122]<1) m[0x120]=m[0x11f];
    if(m[0x11e]==0&&m[0x122]==0&&m[0x11b]==0&&m[0x11f]==0) flag509=0;
}
static float scaleCorrection(int t,int left,int total) {
    const float product=static_cast<float>(sub32(m[t],m[t+1]))*static_cast<float>(m[left]);
    const float quotient=product/static_cast<float>(std::max(1,m[total]));
    return static_cast<float>(m[t])-quotient;
}
static double shift(int t) {
    return static_cast<double>(m[t])-static_cast<double>(sub32(m[t],m[t+1]))*static_cast<double>(m[0x114])/static_cast<double>(std::max(1,m[0x113]));
}
struct Base {
    float x,y,px,py;
    int angle,opacity,r,g,b;
    float waveX,waveY;
};
static void sample(int frame,const Base& base) {
    // The float ratios and degrees are the Android DTO boundary. Native
    // endpoint arithmetic inside that boundary retains original SSE ordering.
    float sx=base.px/100.0f,sy=base.py/100.0f,rotation=base.angle/10.0f;
    int opacity=base.opacity;
    if(flag508) {
        sx=static_cast<float>(static_cast<double>(sx)*static_cast<double>(scaleCorrection(0x115,0x11a,0x119))/100.0);
        sy=static_cast<float>(static_cast<double>(sy)*static_cast<double>(scaleCorrection(0x117,0x11a,0x119))/100.0);
    }
    if(flag509) {
        const int baseAngle=static_cast<int>(std::nearbyint(rotation*10.0f));
        const float angleProduct=static_cast<float>(sub32(m[0x11b],m[0x11c]))*static_cast<float>(m[0x11e]);
        const float angleQuotient=angleProduct/static_cast<float>(std::max(1,m[0x11d]));
        const float angle=static_cast<float>(baseAngle+m[0x11b])-angleQuotient;
        rotation=_mm_cvttss_si32(_mm_set_ss(angle))/10.0f;
        const float product=static_cast<float>(sub32(m[0x11f],m[0x120]))*static_cast<float>(m[0x122]);
        const float quotient=product/static_cast<float>(std::max(1,m[0x121]));
        const float correction=static_cast<float>(m[0x11f])-quotient;
        const float alpha=correction+static_cast<float>(base.opacity);
        opacity=_mm_cvttss_si32(_mm_set_ss(alpha));
    }
    float x=base.x,y=base.y;
    if(flag50a) {
        const float wx=sx+base.waveX/100.0f,wy=sy+base.waveY/100.0f;
        const int angleTenths=static_cast<int>(std::nearbyint(rotation*10.0f));
        const double angle=static_cast<double>(angleTenths)*3.1415926535/1800.0;
        const double dx=(std::cos(angle)*shift(0x10f)*(static_cast<double>(wx)*100.0)+std::sin(-angle)*shift(0x111)*(static_cast<double>(wy)*100.0))/100.0;
        const double dy=(std::sin(angle)*shift(0x10f)*(static_cast<double>(wx)*100.0)+std::cos(angle)*shift(0x111)*(static_cast<double>(wy)*100.0))/100.0;
        x=static_cast<float>(static_cast<double>(x)+dx); y=static_cast<float>(static_cast<double>(y)+dy);
    }
    int color[3]={base.r,base.g,base.b};
    if(m[0xe6]>0) {
        const int half=m[0xe5]/2;
        const double weight=static_cast<double>(half-std::abs(m[0xe6]-half))/static_cast<double>(half);
        for(int c=0;c<3;++c) color[c]=nativeInt(static_cast<double>(((m[0xe4]>>(16-c*8))&255)-100)*2.55*weight+static_cast<double>(color[c]));
    }
    std::cout << "sample\t" << frame << '\t' << sx << '\t' << sy << '\t' << rotation << '\t' << opacity << '\t' << x << '\t' << y;
    for(int c:color) std::cout << '\t' << c;
    for(int i=0x10f;i<=0x122;++i) std::cout << '\t' << m[i];
    for(int i=0xe4;i<=0xe6;++i) std::cout << '\t' << m[i];
    std::cout << '\t' << static_cast<int>(flag50a) << '\t' << static_cast<int>(flag508) << '\t' << static_cast<int>(flag509) << '\n';
}
static void scenario(const char* name,const Base& base) {
    clear();
    std::cout << "case\t" << name << '\t' << base.x << '\t' << base.y << '\t' << base.px << '\t' << base.py << '\t' << base.angle << '\t' << base.opacity << '\t' << base.r << '\t' << base.g << '\t' << base.b << '\t' << base.waveX << '\t' << base.waveY << '\n';
}
int main() {
    _controlfp(_PC_53,_MCW_PC);
    std::cout << std::setprecision(10);
    std::cout << "# W3 original static equations; MSVC x86 /fp:strict /arch:SSE2.\n";
    std::cout << "# Set/tick native memory, endpoint12/13/14, rotated shift11, flash17, then26 exported words.\n";
    for(int duration: {0,1,2,3,4,5,7,11,19,41}) {
        for(int baseAngle: {-36000,-3599,-905,-1,0,375,900,1799,2701,36000}) {
            const Base base{33.25f,-19.5f,83.0f,127.0f,baseAngle,193,17,-93,127,13.0f,-27.0f};
            scenario("all-effects-and-retarget",base);
            set(11,37,-41,0,duration); set(12,173,61,0,duration);
            set(13,-377,0,0,duration); set(14,-211,0,0,duration); set(17,199,-101,61,duration);
            for(int frame=0;frame<55;++frame) {
                if(frame==3) {
                    set(11,-13,19,0,7); set(12,51,-137,0,9); set(13,1811,0,0,11); set(14,113,0,0,13);
                }
                if(frame==17) {
                    set(11,0,0,0,5); set(12,100,100,0,5); set(13,0,0,0,5); set(14,0,0,0,5); set(17,-199,101,-61,5);
                }
                sample(frame,base); tick();
            }
        }
    }
    const Base b{-123.5f,257.25f,75.0f,125.0f,-3599,17,-213,127,-19,0,0};
    scenario("large-retarget-native-imul32",b);
    set(11,900000,-900000,0,360000); set(12,-900000,900000,0,360000);
    set(13,900000,0,0,360000); set(14,-900000,0,0,360000);
    for(int f=0;f<55;++f) { if(f%7==6) { set(11,-900000+f,900000-f,0,360000); set(12,900000-f,-900000+f,0,360000); set(13,-900000+f,0,0,360000); set(14,900000-f,0,0,360000); } sample(f,b); tick(); }
    return 0;
}
